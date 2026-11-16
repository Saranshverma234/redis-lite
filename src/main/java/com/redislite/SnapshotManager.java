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
import java.util.ArrayList;
import java.util.List;
 
/**
 * Saves the store to a file and loads it back.
 *
 * File layout (all integers big-endian, as written by DataOutputStream):
 *   int magic | int version | repeated { boolean true, string key, string value, long expiresAtMs } | boolean false
 * A string is an int byte-length followed by that many UTF-8 bytes.
 *
 * Day 1: saveNow() and load(). The periodic scheduler, the final save on shutdown and the
 * "rename a corrupt file" policy are added on Day 2.
 */
public class SnapshotManager {
 
    private static final int MAGIC = 0x52444C31;          // "RDL1"
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 1_048_576; // 1 MB sanity cap on any single string
 
    private record Row(String key, String value, long expiresAtMs) {}
 
    private final InMemoryStore store;
    private final Path file;
 
    public SnapshotManager(InMemoryStore store, Path file) {
        this.store = store;
        this.file = file;
    }
 
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
 
    /**
     * Loads the snapshot into the store and returns how many entries were restored
     * (entries that expired while the server was down are skipped and not counted).
     * Returns 0 if there is no snapshot file. Throws IOException if the file is damaged.
     * All-or-nothing: the whole file is read and checked BEFORE anything touches the store,
     * so a corrupt file can never leave the store half-loaded.
     */
    public synchronized int load() throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
 
        List<Row> rows = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
 
            if (in.readInt() != MAGIC) {
                throw new IOException("not a redis-lite snapshot file");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
 
            while (in.readBoolean()) {
                String key = readString(in);
                String value = readString(in);
                long expiresAtMs = in.readLong();
                if (expiresAtMs != InMemoryStore.NO_EXPIRY && expiresAtMs <= 0) {
                    throw new IOException("invalid expiry in snapshot: " + expiresAtMs);
                }
                rows.add(new Row(key, value, expiresAtMs));
            }
        } catch (EOFException e) {
            throw new IOException("snapshot file is truncated", e);
        }
 
        int restored = 0;
        for (Row row : rows) {
            if (store.restore(row.key(), row.value(), row.expiresAtMs())) {
                restored++;
            }
        }
        return restored;
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
            throw new IOException("invalid string length in snapshot: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}