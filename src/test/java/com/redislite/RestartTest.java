package com.redislite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts and stops the real application (store + snapshots + TCP server) and talks to it over sockets. */
class RestartTest {

    @TempDir
    Path dir;

    private Path snapshotFile() {
        return dir.resolve("dump.snapshot");
    }

    /** Port 0 = any free port. Interval 0 = no periodic saves, so only the final save on close() persists. */
    private RedisLiteApp newApp(Path file) {
        return new RedisLiteApp(new RedisLiteApp.Config(0, 8, 60_000, 8192, file, 0));
    }

    @Test
    void dataSurvivesARestart() throws Exception {
        try (RedisLiteApp first = newApp(snapshotFile())) {
            first.start();
            try (TestClient c = new TestClient(first.getPort())) {
                assertEquals("+OK", c.send("SET name Saransh"));
                assertEquals("+OK", c.send("SET session abc EX 3600"));
                assertEquals(":1", c.send("INCR visits"));
                assertEquals(":2", c.send("INCR visits"));
            }
        }   // close() stops the server, then writes the final snapshot

        try (RedisLiteApp second = newApp(snapshotFile())) {
            second.start();
            try (TestClient c = new TestClient(second.getPort())) {
                assertEquals("Saransh", c.send("GET name"));
                assertEquals("abc", c.send("GET session"));
                assertEquals("2", c.send("GET visits"));

                String ttlReply = c.send("TTL session");                 // the expiry survived too
                long ttl = Long.parseLong(ttlReply.substring(1));
                assertTrue(ttl > 3590 && ttl <= 3600, "ttl was " + ttl);

                assertEquals(":3", c.send("INCR visits"));               // and the counter keeps counting
            }
        }
    }

    @Test
    void keysThatExpiredWhileTheServerWasDownDoNotComeBack() throws Exception {
        try (RedisLiteApp first = newApp(snapshotFile())) {
            first.start();
            try (TestClient c = new TestClient(first.getPort())) {
                assertEquals("+OK", c.send("SET short v EX 1"));
                assertEquals("+OK", c.send("SET long v"));
            }
        }

        Thread.sleep(1100);          // "the server is down" for longer than the key's lifetime

        try (RedisLiteApp second = newApp(snapshotFile())) {
            second.start();
            try (TestClient c = new TestClient(second.getPort())) {
                assertEquals("$-1", c.send("GET short"));
                assertEquals("v", c.send("GET long"));
            }
        }
    }

    @Test
    void aCorruptSnapshotDoesNotPreventStartup() throws Exception {
        Files.write(snapshotFile(), "garbage, not a snapshot".getBytes());

        try (RedisLiteApp app = newApp(snapshotFile())) {
            app.start();                                                  // must not throw
            try (TestClient c = new TestClient(app.getPort())) {
                assertEquals("$-1", c.send("GET anything"));              // started empty
                assertEquals("+OK", c.send("SET fresh data"));
            }
        }

        assertTrue(Files.exists(dir.resolve("dump.snapshot.corrupt")), "the bad file should be kept for inspection");

        try (RedisLiteApp again = newApp(snapshotFile())) {              // and the new snapshot is valid
            again.start();
            try (TestClient c = new TestClient(again.getPort())) {
                assertEquals("data", c.send("GET fresh"));
            }
        }
    }

    // If the snapshot cannot be READ (as opposed to being damaged), starting empty and saving later
    // could destroy good data. The app must refuse to start and leave the path untouched.
    @Test
    void anUnreadableSnapshotStopsStartupInsteadOfBeingOverwritten() throws Exception {
        Path pathThatIsADirectory = dir.resolve("not-a-file");
        Files.createDirectory(pathThatIsADirectory);

        RedisLiteApp app = newApp(pathThatIsADirectory);
        assertThrows(IOException.class, app::start);
        app.close();                                                      // must not save over it

        assertTrue(Files.isDirectory(pathThatIsADirectory), "close() replaced the unreadable path");
    }

    @Test
    void persistenceCanBeSwitchedOff() throws Exception {
        try (RedisLiteApp first = newApp(null)) {
            first.start();
            try (TestClient c = new TestClient(first.getPort())) {
                assertEquals("+OK", c.send("SET a 1"));
            }
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> found = files.toList();
            assertTrue(found.isEmpty(), "unexpected files: " + found);
        }

        try (RedisLiteApp second = newApp(null)) {
            second.start();
            try (TestClient c = new TestClient(second.getPort())) {
                assertEquals("$-1", c.send("GET a"));
            }
        }
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        RedisLiteApp app = newApp(snapshotFile());
        app.start();
        app.close();
        app.close();
        assertTrue(Files.exists(snapshotFile()));
        assertFalse(Files.exists(dir.resolve("dump.snapshot.tmp")));
    }
}