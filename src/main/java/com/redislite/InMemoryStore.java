package com.redislite;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class InMemoryStore {

    /** Immutable: value and expiry live together, so they always change together. */
    private record Entry(String value, long expiresAtMs) {
        static final long NO_EXPIRY = -1L;

        boolean isExpired(long nowMs) {
            return expiresAtMs != NO_EXPIRY && nowMs >= expiresAtMs;
        }
    }

    private final ConcurrentMap<String, Entry> data = new ConcurrentHashMap<>();

    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * Returns the entry only if it exists and has not expired.
     * An expired entry is removed atomically: the removal re-checks expiry on
     * whatever is in the map right now, so a fresh SET from another thread is never deleted.
     */
    private Entry live(String key) {
        Entry e = data.get(key);
        if (e == null) return null;
        if (!e.isExpired(now())) return e;

        data.computeIfPresent(key, (k, cur) -> cur.isExpired(now()) ? null : cur);
        return null;
    }

    // SET
    public void set(String key, String value) {
        data.put(key, new Entry(value, Entry.NO_EXPIRY)); // one atomic step; also clears any TTL
    }

    // GET
    public String get(String key) {
        Entry e = live(key);
        return e == null ? null : e.value();
    }

    // DELETE
    public void delete(String key) {
        data.remove(key);
    }

    // EXISTS
    public boolean exists(String key) {
        return live(key) != null;
    }

    // EXPIRE
    public boolean expire(String key, long ttlSeconds) {
        if (ttlSeconds <= 0) return false;

        long now = now();
        long expiresAt = Math.addExact(now, Math.multiplyExact(ttlSeconds, 1000L)); // throws on overflow

        // Atomic read-modify-write on this one key. An already-expired key is removed, not revived.
        Entry updated = data.computeIfPresent(key, (k, cur) ->
                cur.isExpired(now) ? null : new Entry(cur.value(), expiresAt));
        return updated != null;
    }

    // TTL
    public long ttl(String key) {
        Entry e = live(key);               // single snapshot, so value and expiry are consistent
        if (e == null) return -2;
        if (e.expiresAtMs() == Entry.NO_EXPIRY) return -1;
        return Math.max(0L, (e.expiresAtMs() - now()) / 1000L);
    }

    // PERSIST
    public boolean persist(String key) {
        long now = now();
        AtomicBoolean changed = new AtomicBoolean(false);

        data.computeIfPresent(key, (k, cur) -> {
            if (cur.isExpired(now)) return null;
            if (cur.expiresAtMs() == Entry.NO_EXPIRY) return cur;
            changed.set(true);
            return new Entry(cur.value(), Entry.NO_EXPIRY);
        });
        return changed.get();
    }

    // INCR
    public long incr(String key) {
        long now = now();
        long[] result = new long[1];

        // compute() is atomic per key, so no synchronized and no extra lock map is needed.
        // Do not call other store methods (set/get) inside this lambda.
        data.compute(key, (k, cur) -> {
            if (cur == null || cur.isExpired(now)) {
                result[0] = 1L;
                return new Entry("1", Entry.NO_EXPIRY);
            }
            long v;
            try {
                v = Long.parseLong(cur.value());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value entered is not a valid integer: " + cur.value());
            }
            result[0] = Math.incrementExact(v);
            return new Entry(String.valueOf(result[0]), cur.expiresAtMs()); // keeps the TTL, like Redis
        });
        return result[0];
    }
}