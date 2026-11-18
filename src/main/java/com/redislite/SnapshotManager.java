package com.redislite;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Saves the store to a file and loads it back.
 *
 * File layout (all integers big-endian, as written by DataOutputStream):
 *   int magic | int version | repeated { boolean true, string key, string value, long expiresAtMs } | boolean false
 * A string is an int byte-length followed by that many UTF-8 bytes.
 *
 * Lifecycle: loadOrQuarantine() at startup, start(intervalMs) for periodic saves,
 * close() to stop the scheduler and write one last snapshot.
 */
public class SnapshotManager implements AutoCloseable {

    /** The file is readable as bytes but is not a valid snapshot (damaged, cut short, or unknown format). */
    public static class CorruptSnapshotException extends IOException {
        public CorruptSnapshotException(String message) {
            super(message);
        }

        public CorruptSnapshotException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int MAGIC = 0x52444C31;          // "RDL1"
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 1_048_576; // 1 MB sanity cap on any single string

    private record Row(String key, String value, long expiresAtMs) {}

    private final InMemoryStore store;
    private final Path file;
    private ScheduledExecutorService scheduler;            // guarded by "this"

    public SnapshotManager(InMemoryStore store, Path file) {
        this.store = store;
        this.file = file;
    }

    // ------------------------------------------------------------------ saving

    /**
     * Writes all live entries to the snapshot file and returns how many were written.
     * Writes to a temp file first and renames it over the real file, so a crash mid-write
     * can never destroy the previous good snapshot. synchronized: only one save at a time.
     */
    public synchronized int saveNow() throws IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path tmp = absolute.resolveSibling(absolute.getFileName() + ".tmp");

        int[] count = {0};
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {

                out.writeInt(MAGIC);
                out.writeInt(VERSION);

                // Weakly consistent: clients keep writing while we iterate. Each entry is
                // intact (entries are immutable) but this is not one instant in time.
                store.forEachLiveEntry((key, value, expiresAtMs) -> {
                    out.writeBoolean(true);
                    writeString(out, key);
                    writeString(out, value);
                    out.writeLong(expiresAtMs);
                    count[0]++;
                });

                out.writeBoolean(false);     // end marker: its absence means the file was cut short
                out.flush();
                fos.getFD().sync();          // force the bytes to disk BEFORE the rename
            }
            moveIntoPlace(tmp, absolute);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);       // never leave a half-written temp file around
            throw e;
        }
        return count[0];
    }

    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Starts saving every intervalMs milliseconds on a background thread. Call at most once. */
    public synchronized void start(long intervalMs) {
        if (scheduler != null) {
            throw new IllegalStateException("snapshot scheduler already started");
        }
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-saver");
            t.setDaemon(true);
            return t;
        });
        // Fixed DELAY: the next save starts intervalMs after the previous one FINISHED,
        // so a slow save can never overlap with the next one.
        scheduler.scheduleWithFixedDelay(this::saveQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * A failed periodic save must never kill the schedule: if a scheduled task throws,
     * Java silently cancels ALL future runs. So catch everything and just log it.
     */
    private void saveQuietly() {
        try {
            saveNow();
        } catch (Throwable t) {
            // Throwable, not just Exception: an Error escaping a scheduled task would also cancel
            // every future run silently. This task only saves and logs, so surviving is safe.
            System.err.println("periodic snapshot failed: " + t);
        }
    }

    /** Stops the periodic saves, then writes one final snapshot. */
    @Override
    public void close() {
        ScheduledExecutorService toStop;
        synchronized (this) {
            toStop = scheduler;
            scheduler = null;
        }
        // Wait OUTSIDE the lock: a save in progress needs this object's lock to finish,
        // so waiting for it while holding the lock would deadlock.
        if (toStop != null) {
            toStop.shutdown();               // lets a running save finish, cancels future ones
            try {
                if (!toStop.awaitTermination(10, TimeUnit.SECONDS)) {
                    toStop.shutdownNow();
                }
            } catch (InterruptedException e) {
                toStop.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        try {
            saveNow();
        } catch (IOException e) {
            System.err.println("final snapshot failed: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- loading

    /**
     * Loads the snapshot into the store and returns how many entries were restored
     * (entries that expired while the server was down are skipped and not counted).
     * Returns 0 if there is no snapshot file. Throws CorruptSnapshotException if the file is
     * damaged, or another IOException if it cannot be read at all.
     * All-or-nothing: the whole file is read and checked BEFORE anything touches the store,
     * so a corrupt file can never leave the store half-loaded.
     */
    public synchronized int load() throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }

        // Keyed by key: a save made while clients were writing can contain the same key twice
        // (delete + re-set during iteration). The later row is the newer value, so it wins.
        Map<String, Row> rows = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {

            if (in.readInt() != MAGIC) {
                throw new CorruptSnapshotException("not a redis-lite snapshot file");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new CorruptSnapshotException("unsupported snapshot version " + version);
            }

            while (in.readBoolean()) {
                String key = readString(in);
                String value = readString(in);
                long expiresAtMs = in.readLong();
                if (expiresAtMs != InMemoryStore.NO_EXPIRY && expiresAtMs <= 0) {
                    throw new CorruptSnapshotException("invalid expiry in snapshot: " + expiresAtMs);
                }
                rows.put(key, new Row(key, value, expiresAtMs));
            }
        } catch (EOFException e) {
            throw new CorruptSnapshotException("snapshot file is truncated", e);
        }

        int restored = 0;
        for (Row row : rows.values()) {
            if (store.restore(row.key(), row.value(), row.expiresAtMs())) {
                restored++;
            }
        }
        return restored;
    }

    /**
     * Startup-friendly load. If the file is DAMAGED, it is renamed to "<name>.corrupt" (kept for
     * inspection, and so the next save cannot overwrite it) and the store starts empty.
     * If the file merely cannot be READ (permissions, disk error) the IOException is rethrown:
     * starting empty there could later overwrite a perfectly good snapshot.
     */
    public synchronized int loadOrQuarantine() throws IOException {
        try {
            return load();
        } catch (CorruptSnapshotException e) {
            Path corrupt = file.toAbsolutePath().resolveSibling(file.getFileName() + ".corrupt");
            System.err.println("Snapshot " + file + " is unusable (" + e.getMessage()
                    + "); moving it to " + corrupt + " and starting empty.");
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
            return 0;
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("string too large to snapshot (" + bytes.length + " bytes)");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            // A damaged length field must not make us allocate gigabytes.
            throw new CorruptSnapshotException("invalid string length in snapshot: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}