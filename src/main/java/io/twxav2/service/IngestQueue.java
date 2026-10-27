package io.twxav2.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Queue for intakes (upload or BPM import). Multiple intakes run
 * in parallel (bounded by twxav2.ingest.concurrency), the rest line up.
 * The status lives server-side so the UI can show it across page
 * changes and refreshes; terminal states stay visible for a while.
 */
@ApplicationScoped
public class IngestQueue {

    private static final Logger LOG = Logger.getLogger(IngestQueue.class.getName());

    public enum Phase { QUEUED, UPLOAD, EXPORT, PARSE, ANALYZE, DONE, ERROR }

    /** Running or finished task. Mutable, updated by worker threads. */
    public static final class Task {
        final long id;
        final String label;
        final String dedupKey;
        final Phase firstPhase;
        volatile Phase phase;
        volatile long startedAt;
        volatile long finishedAt;
        volatile Long snapshotId;
        volatile String error;
        volatile boolean started;
        final Reservation reservation;
        final Runnable cleanupIfNotStarted;

        Task(long id, String label, String dedupKey, Phase firstPhase,
             Reservation reservation, Runnable cleanupIfNotStarted) {
            this.id = id;
            this.label = label;
            this.dedupKey = dedupKey;
            this.firstPhase = firstPhase;
            this.phase = Phase.QUEUED;
            this.reservation = reservation;
            this.cleanupIfNotStarted = cleanupIfNotStarted;
        }

        /** Report an intermediate phase (only while the task is not terminal). */
        public void advance(Phase p) {
            if (phase != Phase.DONE && phase != Phase.ERROR) {
                phase = p;
            }
        }

        void done(Long sid) {
            snapshotId = sid;
            phase = Phase.DONE;
            finishedAt = System.currentTimeMillis();
        }

        public void fail(String message) {
            error = message;
            phase = Phase.ERROR;
            finishedAt = System.currentTimeMillis();
        }
    }

    /** Capacity and temporary-work budget reserved before request data is copied into staging. */
    public static final class Reservation implements AutoCloseable {
        private final IngestQueue owner;
        private final long bytes;
        private boolean released;

        private Reservation(IngestQueue owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }

    public static final class QueueFullException extends RuntimeException {
        public QueueFullException() { super("Ingest capacity exhausted"); }
    }

    public record TaskStatus(long taskId, boolean active, String phase, String label,
                             long startedAt, Long snapshotId, String error, int queuePosition) { }

    /** Terminal states stay visible this long so a fresh client can show them. */
    private static final long TERMINAL_VISIBLE_MS = 120_000;

    @ConfigProperty(name = "twxav2.ingest.concurrency", defaultValue = "2")
    int concurrency;

    @ConfigProperty(name = "twxav2.ingest.queue-capacity", defaultValue = "4")
    int queueCapacity;

    @ConfigProperty(name = "twxav2.ingest.max-work-bytes", defaultValue = "4294967296")
    long maxWorkBytes;

    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private ExecutorService pool;
    private long reservedWorkBytes;
    private int reservedTasks;

    @PostConstruct
    void init() {
        int workers = Math.max(1, concurrency);
        if (queueCapacity < 1 || maxWorkBytes < 1) {
            throw new IllegalArgumentException("Ingest queue capacity and work-byte budget must be positive");
        }
        pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity));
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
            for (Task task : tasks) {
                if (!task.started && !isTerminal(task)) {
                    runCleanup(task.cleanupIfNotStarted);
                    task.fail("cancelled during application shutdown");
                    task.reservation.close();
                }
            }
        }
    }

    /** Reserve task-count and staged-byte capacity before copying/uploading a file. */
    public synchronized Reservation reserve(long bytes) {
        int taskLimit = Math.max(1, concurrency) + queueCapacity;
        if (bytes < 0 || bytes > maxWorkBytes || reservedTasks >= taskLimit
                || reservedWorkBytes > maxWorkBytes - bytes) {
            return null;
        }
        reservedTasks++;
        reservedWorkBytes += bytes;
        return new Reservation(this, bytes);
    }

    /** Is a task with the same dedupKey already running or waiting? */
    public boolean isPending(String dedupKey) {
        return findPending(dedupKey) != null;
    }

    /**
     * Enqueues a task. job receives the Task (for intermediate phases) and returns
     * the snapshot ID; a null return = failed (message set via fail beforehand).
     *
     * @return task ID, or null if the dedupKey is already queued
     */
    public synchronized Long submit(String label, String dedupKey, Phase firstPhase,
                                    Reservation reservation, Runnable cleanupIfNotStarted,
                                    Function<Task, Long> job) {
        if (reservation == null || reservation.owner != this || reservation.released) {
            throw new IllegalArgumentException("A live ingest reservation is required");
        }
        if (findPending(dedupKey) != null) {
            release(reservation);
            return null;
        }
        // Label comes from user input (filename/import): strip control chars,
        // otherwise log spoofing (CR/LF) in the SEVERE line and in the task status
        String safeLabel = label == null ? null : label.replaceAll("\\p{Cntrl}", "_");
        Task t = new Task(ids.incrementAndGet(), safeLabel, dedupKey, firstPhase,
                reservation, cleanupIfNotStarted);
        t.startedAt = System.currentTimeMillis();
        tasks.add(t);
        try {
            pool.execute(() -> {
                t.started = true;
                t.phase = t.firstPhase;
                try {
                    Long sid = job.apply(t);
                    if (sid != null) {
                        t.done(sid);
                    } else if (t.phase != Phase.ERROR) {
                        t.fail("failed");
                    }
                } catch (Exception | Error e) {
                    // full stack into the log; the task error shows the root cause
                    // (CDI proxies otherwise wrap target exceptions in ArcUndeclaredThrowable;
                    // errors like StackOverflowError would otherwise leave the task stuck forever)
                    LOG.log(Level.SEVERE, "Ingest job failed: " + t.label, e);
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    t.fail(root == e
                            ? e.getClass().getSimpleName() + ": " + e.getMessage()
                            : e.getClass().getSimpleName() + " / "
                                    + root.getClass().getSimpleName() + ": " + root.getMessage());
                } finally {
                    release(reservation);
                }
            });
        } catch (RejectedExecutionException e) {
            tasks.remove(t);
            runCleanup(cleanupIfNotStarted);
            release(reservation);
            throw new QueueFullException();
        }
        return t.id;
    }

    private synchronized void release(Reservation reservation) {
        if (reservation == null || reservation.released) {
            return;
        }
        reservation.released = true;
        reservedTasks--;
        reservedWorkBytes -= reservation.bytes;
        if (reservedTasks < 0 || reservedWorkBytes < 0) {
            throw new IllegalStateException("Ingest reservation accounting underflow");
        }
    }

    private static void runCleanup(Runnable cleanup) {
        if (cleanup != null) {
            try {
                cleanup.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not clean a staged ingest file", e);
            }
        }
    }

    /** Active, waiting and recently finished tasks, oldest first. */
    public List<TaskStatus> list() {
        long now = System.currentTimeMillis();
        tasks.removeIf(t -> isTerminal(t) && now - t.finishedAt > TERMINAL_VISIBLE_MS);
        return tasks.stream()
                .sorted((a, b) -> Long.compare(a.startedAt, b.startedAt))
                .map(t -> {
                    int pos = 0;
                    if (t.phase == Phase.QUEUED) {
                        for (Task o : tasks) {
                            if (o.phase == Phase.QUEUED && o.startedAt < t.startedAt) {
                                pos++;
                            }
                        }
                        pos++; // 1 = next up
                    }
                    return new TaskStatus(t.id, !isTerminal(t), t.phase.name(), t.label,
                            t.startedAt, t.snapshotId, t.error, pos);
                })
                .toList();
    }

    private static boolean isTerminal(Task t) {
        return t.phase == Phase.DONE || t.phase == Phase.ERROR;
    }

    private Task findPending(String dedupKey) {
        if (dedupKey == null) {
            return null;
        }
        for (Task t : tasks) {
            if (!isTerminal(t) && dedupKey.equals(t.dedupKey)) {
                return t;
            }
        }
        return null;
    }
}
