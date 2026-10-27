package io.twxav2.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

        Task(long id, String label, String dedupKey, Phase firstPhase) {
            this.id = id;
            this.label = label;
            this.dedupKey = dedupKey;
            this.firstPhase = firstPhase;
            this.phase = Phase.QUEUED;
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

    public record TaskStatus(long taskId, boolean active, String phase, String label,
                             long startedAt, Long snapshotId, String error, int queuePosition) { }

    /** Terminal states stay visible this long so a fresh client can show them. */
    private static final long TERMINAL_VISIBLE_MS = 120_000;

    @ConfigProperty(name = "twxav2.ingest.concurrency", defaultValue = "2")
    int concurrency;

    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private ExecutorService pool;

    @PostConstruct
    void init() {
        pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
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
                                    Function<Task, Long> job) {
        if (findPending(dedupKey) != null) {
            return null;
        }
        // Label comes from user input (filename/import): strip control chars,
        // otherwise log spoofing (CR/LF) in the SEVERE line and in the task status
        String safeLabel = label == null ? null : label.replaceAll("\\p{Cntrl}", "_");
        Task t = new Task(ids.incrementAndGet(), safeLabel, dedupKey, firstPhase);
        t.startedAt = System.currentTimeMillis();
        tasks.add(t);
        pool.execute(() -> {
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
            }
        });
        return t.id;
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
