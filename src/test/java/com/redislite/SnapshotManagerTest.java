package com.redislite;
 
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
 
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
class SnapshotManagerTest {
 
    @TempDir
    Path dir;     // a fresh temp folder per test, outside your OneDrive project folder
 
    private Path file() {
        return dir.resolve("dump.snapshot");
    }
 
    private SnapshotManager managerFor(InMemoryStore store) {
        return new SnapshotManager(store, file());
    }
 
    @Test
    void roundTripRestoresValuesAndTtls() throws Exception {
        InMemoryStore original = new InMemoryStore();
        original.set("a", "1");
        original.set("b", "2", 60);
 
        assertEquals(2, managerFor(original).saveNow());
 
        InMemoryStore restored = new InMemoryStore();
        assertEquals(2, managerFor(restored).load());
 
        assertEquals("1", restored.get("a"));
        assertEquals("2", restored.get("b"));
        assertEquals(-1, restored.ttl("a"));                        // no expiry stays no expiry
        long ttl = restored.ttl("b");
        assertTrue(ttl > 0 && ttl <= 60, "ttl was " + ttl);         // expiry survived
    }
 
    @Test
    void anEmptyStoreRoundTrips() throws Exception {
        assertEquals(0, managerFor(new InMemoryStore()).saveNow());
        assertTrue(Files.exists(file()));
 
        InMemoryStore restored = new InMemoryStore();
        assertEquals(0, managerFor(restored).load());
        assertEquals(0, restored.size());
    }
 
    @Test
    void loadWithNoFileRestoresNothing() throws Exception {
        InMemoryStore store = new InMemoryStore();
        assertEquals(0, managerFor(store).load());
        assertEquals(0, store.size());
    }
 
    @Test
    void unicodeAndEmptyStringsSurvive() throws Exception {
        String key = "k\u00e9y-\u4e16\u754c";
        String value = "v\u00e4lue-\u2713";
 
        InMemoryStore original = new InMemoryStore();
        original.set(key, value);
        original.set("empty", "");
        managerFor(original).saveNow();
 
        InMemoryStore restored = new InMemoryStore();
        managerFor(restored).load();
        assertEquals(value, restored.get(key));
        assertEquals("", restored.get("empty"));
    }
 
    @Test
    void expiredEntriesAreNotSaved() throws Exception {
        InMemoryStore original = new InMemoryStore();
        original.set("gone", "v", 1);
        original.set("stays", "v");
        Thread.sleep(1100);
 
        assertEquals(1, managerFor(original).saveNow());     // only "stays" was written
 
        InMemoryStore restored = new InMemoryStore();
        managerFor(restored).load();
        assertNull(restored.get("gone"));
        assertEquals("v", restored.get("stays"));
        assertEquals(1, restored.size());
    }
 
    @Test
    void entriesThatExpireWhileTheServerIsDownAreDropped() throws Exception {
        InMemoryStore original = new InMemoryStore();
        original.set("short", "v", 1);
        assertEquals(1, managerFor(original).saveNow());     // alive at save time
 
        Thread.sleep(1100);                                  // "the server is down" for over a second
 
        InMemoryStore restored = new InMemoryStore();
        assertEquals(0, managerFor(restored).load());        // expired during the downtime
        assertNull(restored.get("short"));
        assertEquals(0, restored.size());
    }
 
    @Test
    void noTempFileIsLeftBehindAndASecondSaveReplacesTheFirst() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SnapshotManager manager = managerFor(store);
 
        store.set("a", "old");
        manager.saveNow();
        store.set("a", "new");
        store.set("b", "2");
        manager.saveNow();
 
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("dump.snapshot"), names, "unexpected files: " + names);
        }
 
        InMemoryStore restored = new InMemoryStore();
        managerFor(restored).load();
        assertEquals("new", restored.get("a"));
        assertEquals("2", restored.get("b"));
    }
 
    @Test
    void garbageFilesAreRejected() throws Exception {
        Files.write(file(), "this is definitely not a snapshot".getBytes());
 
        InMemoryStore store = new InMemoryStore();
        assertThrows(IOException.class, () -> managerFor(store).load());
        assertEquals(0, store.size());
    }
 
    @Test
    void unknownVersionsAreRejected() throws Exception {
        try (OutputStream raw = Files.newOutputStream(file());
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(0x52444C31);   // correct magic
            out.writeInt(99);           // version this code does not know
            out.writeBoolean(false);
        }
 
        InMemoryStore store = new InMemoryStore();
        assertThrows(IOException.class, () -> managerFor(store).load());
    }
 
    // The important one: a cut-off file must not leave the store half-loaded.
    @Test
    void truncatedFilesAreRejectedAndLoadNothing() throws Exception {
        InMemoryStore original = new InMemoryStore();
        for (int i = 0; i < 100; i++) {
            original.set("key" + i, "value" + i);
        }
        managerFor(original).saveNow();
 
        byte[] bytes = Files.readAllBytes(file());
        Files.write(file(), java.util.Arrays.copyOf(bytes, bytes.length / 2));
 
        InMemoryStore store = new InMemoryStore();
        assertThrows(IOException.class, () -> managerFor(store).load());
        assertEquals(0, store.size(), "a failed load must not leave partial data behind");
    }
 
    // Saving while clients write must not throw, and the result must be a loadable file.
    @Test
    void savingWhileWritersRunIsSafe() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SnapshotManager manager = managerFor(store);
 
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
 
        for (int t = 0; t < 8; t++) {
            Thread w = new Thread(() -> {
                try {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    while (!stop.get()) {
                        String key = "k" + r.nextInt(500);
                        switch (r.nextInt(3)) {
                            case 0 -> store.set(key, "v" + r.nextInt(1000));
                            case 1 -> store.set(key, "v", 60);
                            case 2 -> store.delete(key);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            writers.add(w);
            w.start();
        }
 
        try {
            for (int i = 0; i < 20; i++) {
                manager.saveNow();
            }
        } finally {
            stop.set(true);
            for (Thread w : writers) {
                w.join();
            }
        }
 
        assertNull(failure.get(), () -> "a writer failed: " + failure.get());
 
        InMemoryStore restored = new InMemoryStore();
        int n = managerFor(restored).load();
        assertTrue(n <= 500, "more entries than keys exist: " + n);
        assertEquals(n, restored.size());
    }


    @Test
void duplicateKeysInAFileKeepTheLaterRow() throws Exception {
    try (OutputStream raw = Files.newOutputStream(file());
         DataOutputStream out = new DataOutputStream(raw)) {
        out.writeInt(0x52444C31);                  // magic
        out.writeInt(1);                           // version
        for (String value : new String[] {"old", "new"}) {
            out.writeBoolean(true);
            byte[] k = "k".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(k.length);
            out.write(k);
            byte[] v = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(v.length);
            out.write(v);
            out.writeLong(-1L);                    // no expiry
        }
        out.writeBoolean(false);                   // end marker
    }

    InMemoryStore store = new InMemoryStore();
    assertEquals(1, managerFor(store).load());
    assertEquals("new", store.get("k"));
}
}