package com.redislite;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreConcurrencyTest {

    private static final int THREADS = 20;

    @FunctionalInterface
    interface ThreadTask {
        void run(int threadId) throws Exception;
    }

    /**
     * Runs the task on `threads` threads. All threads wait at a starting line and are
     * released together, so they really overlap. If any thread throws (including a failed
     * assertion), the exception is rethrown here and the test fails.
     */
    private void runConcurrently(int threads, ThreadTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                task.run(id);
                return null;
            }));
        }

        ready.await();
        start.countDown();
        try {
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // 1) No lost updates: 20 threads x 1000 increments must give exactly 20000.
    @Test
    void concurrentIncrIsExact() throws Exception {
        InMemoryStore store = new InMemoryStore();
        int perThread = 1000;

        runConcurrently(THREADS, id -> {
            for (int i = 0; i < perThread; i++) {
                store.incr("counter");
            }
        });

        assertEquals(String.valueOf(THREADS * perThread), store.get("counter"));
    }

    // 2) incr must keep the TTL, even while many threads increment at once.
    @Test
    void concurrentIncrKeepsTtl() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.set("counter", "0");
        assertTrue(store.expire("counter", 60));

        runConcurrently(THREADS, id -> {
            for (int i = 0; i < 500; i++) {
                store.incr("counter");
            }
        });

        assertEquals(String.valueOf(THREADS * 500), store.get("counter"));
        long ttl = store.ttl("counter");
        assertTrue(ttl > 0 && ttl <= 60, "TTL should still be set, was " + ttl);
    }

    // 3) A get() that cleans up an expired key must never delete a value set by another thread.
    @Test
    void getNeverDeletesAFreshValue() throws Exception {
        InMemoryStore store = new InMemoryStore();
        int n = 200;

        for (int i = 0; i < n; i++) {
            store.set("k" + i, "old");
            store.expire("k" + i, 1);
        }
        Thread.sleep(1100); // all keys are now expired but still sitting in the map

        // Even-numbered threads read (triggering cleanup), odd-numbered threads write.
        runConcurrently(THREADS, id -> {
            for (int i = 0; i < n; i++) {
                if (id % 2 == 0) {
                    store.get("k" + i);
                } else {
                    store.set("k" + i, "fresh");
                }
            }
        });

        // Every key was set at least once, and no cleanup may remove a fresh value.
        for (int i = 0; i < n; i++) {
            assertEquals("fresh", store.get("k" + i), "lost write on key k" + i);
        }
    }

    // 4) Regression test: EXPIRE must not bring an expired key back to life.
    @Test
    void expireDoesNotReviveExpiredKey() throws Exception {
        InMemoryStore store = new InMemoryStore();
        store.set("key", "value");
        assertTrue(store.expire("key", 1));
        Thread.sleep(1100);

        assertFalse(store.expire("key", 100), "expire on an expired key should fail");
        assertNull(store.get("key"));
        assertEquals(-2, store.ttl("key"));
    }

    // 5) Mixed stress test: random operations on a few shared keys.
    //    Nothing may throw, and ttl() may only return -2, -1, or 0..3.
    @Test
    void mixedOperationsStress() throws Exception {
        InMemoryStore store = new InMemoryStore();
        String[] keys = {"a", "b", "c"};

        runConcurrently(THREADS, id -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 20_000; i++) {
                String key = keys[r.nextInt(keys.length)];
                switch (r.nextInt(7)) {
                    case 0 -> store.set(key, String.valueOf(r.nextInt(100)));
                    case 1 -> store.get(key);
                    case 2 -> store.delete(key);
                    case 3 -> store.expire(key, 1 + r.nextInt(3));
                    case 4 -> store.persist(key);
                    case 5 -> {
                        long t = store.ttl(key);
                        assertTrue(t == -2 || t == -1 || (t >= 0 && t <= 3),
                                "impossible ttl value: " + t);
                    }
                    case 6 -> store.incr(key); // values are always numeric, so this must not throw
                }
            }
        });
    }
}
