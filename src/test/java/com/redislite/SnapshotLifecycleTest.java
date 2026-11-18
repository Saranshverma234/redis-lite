package com.redislite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SnapshotLifecycleTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("dump.snapshot");
    }

    private Path corruptFile() {
        return dir.resolve("dump.snapshot.corrupt");
    }

    /**
     * Polls until a fresh load of the snapshot file shows key=expected. Reading while a save is
     * renaming the file can fail briefly (especially on Windows), so read errors just retry.
     */
    private void awaitValue(String key, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String actual = null;
        while (System.currentTimeMillis() < deadline) {
            InMemoryStore probe = new InMemoryStore();
            try {
                new SnapshotManager(probe, file()).load();
                actual = probe.get(key);
            } catch (IOException ignored) {
                // file missing, or mid-replacement: try again
            }
            if (expected.equals(actual)) {
                return;
            }
            Thread.sleep(50);
        }
        long size = Files.exists(file()) ? file().toFile().length() : -1;
        fail("snapshot never showed " + key + "=" + expected + " (last seen: " + actual
                + ", file exists: " + Files.exists(file()) + ", file size: " + size + ")");
    }

    // ---------------------------------------------------------------- scheduler

    @Test
    void periodicSavesHappenWithoutAnyoneAskingForThem() throws Exception {
        InMemoryStore store = new InMemoryStore();
        try (SnapshotManager manager = new SnapshotManager(store, file())) {
            store.set("a", "1");
            manager.start(100);
            awaitValue("a", "1", 5000);
        }
    }

    @Test
    void laterChangesReachTheFileOnTheNextCycle() throws Exception {
        InMemoryStore store = new InMemoryStore();
        try (SnapshotManager manager = new SnapshotManager(store, file())) {
            store.set("a", "1");
            manager.start(100);
            awaitValue("a", "1", 5000);

            store.set("a", "2");
            awaitValue("a", "2", 5000);
        }
    }

    @Test
    void startingTwiceIsAnError() {
        InMemoryStore store = new InMemoryStore();
        try (SnapshotManager manager = new SnapshotManager(store, file())) {
            manager.start(60_000);
            assertThrows(IllegalStateException.class, () -> manager.start(60_000));
        }
    }

    // A failing save must not silently cancel the schedule (the classic scheduled-task trap).
    @Test
    void aFailingSaveDoesNotKillTheSchedule() throws Exception {
        InMemoryStore store = new InMemoryStore();
        try (SnapshotManager manager = new SnapshotManager(store, file())) {
            store.set("small", "ok");
            store.set("huge", "x".repeat(1_048_577));   // over the 1 MB cap, so every save fails

            manager.start(100);
            Thread.sleep(500);                           // several failing cycles (errors go to stderr)
            assertFalse(Files.exists(file()), "a failed save must not produce a snapshot");

            store.delete("huge");                        // now saves can succeed
            awaitValue("small", "ok", 5000);             // the schedule is still alive
        }
    }

    // ------------------------------------------------------------- final save

    @Test
    void closeWritesAFinalSnapshot() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SnapshotManager manager = new SnapshotManager(store, file());
        manager.start(60_000);              // far too long to fire during this test

        store.set("last-minute", "write");
        assertFalse(Files.exists(file()));
        manager.close();

        InMemoryStore restored = new InMemoryStore();
        new SnapshotManager(restored, file()).load();
        assertEquals("write", restored.get("last-minute"));
    }

    @Test
    void closeStopsThePeriodicSaves() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SnapshotManager manager = new SnapshotManager(store, file());
        store.set("a", "1");
        manager.start(50);
        manager.close();

        Files.deleteIfExists(file());
        Thread.sleep(400);                   // plenty of time for a stray scheduled save
        assertFalse(Files.exists(file()), "a save ran after close()");
    }

    // ------------------------------------------------------------- quarantine

    @Test
    void aValidFileIsLoadedAndLeftInPlace() throws Exception {
        InMemoryStore original = new InMemoryStore();
        original.set("a", "1");
        new SnapshotManager(original, file()).saveNow();

        InMemoryStore store = new InMemoryStore();
        assertEquals(1, new SnapshotManager(store, file()).loadOrQuarantine());
        assertEquals("1", store.get("a"));
        assertTrue(Files.exists(file()));
        assertFalse(Files.exists(corruptFile()));
    }

    @Test
    void aGarbageFileIsMovedAsideAndTheStoreStartsEmpty() throws Exception {
        byte[] garbage = "this is definitely not a snapshot".getBytes();
        Files.write(file(), garbage);

        InMemoryStore store = new InMemoryStore();
        SnapshotManager manager = new SnapshotManager(store, file());
        assertEquals(0, manager.loadOrQuarantine());

        assertEquals(0, store.size());
        assertFalse(Files.exists(file()), "the bad file should have been moved away");
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(corruptFile())), "the evidence must be preserved");

        // The next save creates a fresh snapshot and does not touch the .corrupt copy.
        store.set("fresh", "data");
        manager.saveNow();
        assertTrue(Files.exists(file()));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(corruptFile())));
    }

    @Test
    void aTruncatedFileIsMovedAsideToo() throws Exception {
        InMemoryStore original = new InMemoryStore();
        for (int i = 0; i < 50; i++) {
            original.set("key" + i, "value" + i);
        }
        new SnapshotManager(original, file()).saveNow();

        byte[] bytes = Files.readAllBytes(file());
        Files.write(file(), Arrays.copyOf(bytes, bytes.length / 2));

        InMemoryStore store = new InMemoryStore();
        assertEquals(0, new SnapshotManager(store, file()).loadOrQuarantine());
        assertEquals(0, store.size(), "a damaged file must not leave partial data in the store");
        assertTrue(Files.exists(corruptFile()));
    }

    @Test
    void aMissingFileIsNotAnErrorAndNothingIsMoved() throws Exception {
        InMemoryStore store = new InMemoryStore();
        assertEquals(0, new SnapshotManager(store, file()).loadOrQuarantine());
        assertFalse(Files.exists(corruptFile()));
    }
}