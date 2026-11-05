package com.redislite;
 
import org.junit.jupiter.api.Test;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
 
class KvServerLimitsTest {
 
    private static KvServer newServer(InMemoryStore store, int idleTimeoutMs, int maxLineBytes) {
        return new KvServer(new CommandProcessor(store), 0, 4, idleTimeoutMs, maxLineBytes);
    }
 
    // A client that stays silent too long is told so and disconnected.
    @Test
    void idleClientsAreDisconnected() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 300, 8192)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                // The client sends nothing; after ~300 ms the server speaks and hangs up.
                assertEquals("-ERR idle timeout", c.in.readLine());
                assertNull(c.in.readLine(), "server should close an idle connection");
            }
        }
    }
 
    // The timeout is about silence, not session length: an active client must not be cut off.
    @Test
    void activeClientsAreNotDisconnected() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 500, 8192)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                for (int i = 1; i <= 10; i++) {          // about 1 second in total, longer than the timeout
                    assertEquals(":" + i, c.send("INCR n"));
                    Thread.sleep(100);                   // but never silent for 500 ms
                }
            }
        }
    }
 
    // A line over the limit is refused, the connection is closed, and the server carries on.
    @Test
    void oversizedLinesAreRejected() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 5000, 64)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                String reply = c.send("SET k " + "x".repeat(100_000));
                assertEquals("-ERR line too long", reply);
                assertNull(c.in.readLine(), "server should close the connection after an oversized line");
            }
 
            // Nobody else is affected, and nothing was stored.
            try (TestClient other = new TestClient(server.getPort())) {
                assertEquals("$-1", other.send("GET k"));
                assertEquals("+OK", other.send("SET ok 1"));
            }
        }
    }
 
    // Boundary check: exactly the limit is fine, one byte more is not.
    @Test
    void aLineExactlyAtTheLimitIsAccepted() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 5000, 64)) {
            server.start();
 
            String atLimit = "GET " + "k".repeat(60);           // 64 bytes
            String overLimit = "GET " + "k".repeat(61);          // 65 bytes
 
            try (TestClient c = new TestClient(server.getPort())) {
                assertEquals("$-1", c.send(atLimit));
            }
            try (TestClient c = new TestClient(server.getPort())) {
                assertEquals("-ERR line too long", c.send(overLimit));
            }
        }
    }
 
    // A client that disconnects halfway through a command must not hurt the server.
    @Test
    void aHalfWrittenLineIsIgnoredWhenTheClientLeaves() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 5000, 8192)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                c.sendPartial("SET half");     // no newline, then the client disconnects
            }
 
            try (TestClient d = new TestClient(server.getPort())) {
                assertEquals("$-1", d.send("GET half"));    // the half command was never executed
                assertEquals("+OK", d.send("SET ok 1"));
            }
        }
    }
}