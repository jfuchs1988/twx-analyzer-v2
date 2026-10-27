package io.twxav2.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class IngestQueueTest {

    @Test
    void boundsConcurrentAndQueuedWorkByCountAndBytes() throws Exception {
        IngestQueue queue = queue(1, 1, 10);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        try {
            IngestQueue.Reservation first = queue.reserve(6);
            assertNotNull(first);
            assertNotNull(queue.submit("first", "first", IngestQueue.Phase.UPLOAD,
                    first, null, task -> {
                        firstStarted.countDown();
                        await(releaseFirst);
                        return 1L;
                    }));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            IngestQueue.Reservation second = queue.reserve(4);
            assertNotNull(second);
            assertNotNull(queue.submit("second", "second", IngestQueue.Phase.UPLOAD,
                    second, null, task -> {
                        secondFinished.countDown();
                        return 2L;
                    }));
            assertNull(queue.reserve(1), "byte and task budgets are already fully reserved");

            releaseFirst.countDown();
            assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            IngestQueue.Reservation afterCompletion = null;
            while (afterCompletion == null && System.nanoTime() < deadline) {
                afterCompletion = queue.reserve(10);
                if (afterCompletion == null) {
                    Thread.sleep(10);
                }
            }
            assertNotNull(afterCompletion, "reservations are released when jobs finish");
            afterCompletion.close();
        } finally {
            releaseFirst.countDown();
            queue.shutdown();
        }
    }

    @Test
    void shutdownCleansQueuedStagingAndReleasesReservation() throws Exception {
        IngestQueue queue = queue(1, 1, 10);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch keepFirstRunning = new CountDownLatch(1);
        CountDownLatch firstExited = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        try {
            IngestQueue.Reservation first = queue.reserve(5);
            assertNotNull(queue.submit("running", "running", IngestQueue.Phase.UPLOAD,
                    first, null, task -> {
                        firstStarted.countDown();
                        try {
                            await(keepFirstRunning);
                            return 1L;
                        } finally {
                            firstExited.countDown();
                        }
                    }));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            IngestQueue.Reservation queued = queue.reserve(5);
            assertNotNull(queue.submit("queued", "queued", IngestQueue.Phase.UPLOAD,
                    queued, () -> cleaned.set(true), task -> 2L));
            queue.shutdown();
            assertTrue(cleaned.get());
            assertTrue(firstExited.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            IngestQueue.Reservation afterShutdown = null;
            while (afterShutdown == null && System.nanoTime() < deadline) {
                afterShutdown = queue.reserve(10);
                if (afterShutdown == null) {
                    Thread.sleep(10);
                }
            }
            assertNotNull(afterShutdown, "shutdown releases reserved capacity");
            afterShutdown.close();
        } finally {
            keepFirstRunning.countDown();
            queue.shutdown();
        }
    }

    private static IngestQueue queue(int concurrency, int queueCapacity, long workBytes) {
        IngestQueue queue = new IngestQueue();
        queue.concurrency = concurrency;
        queue.queueCapacity = queueCapacity;
        queue.maxWorkBytes = workBytes;
        queue.init();
        return queue;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
