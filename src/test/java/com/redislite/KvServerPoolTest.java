package com.redislite;
 
import org.junit.jupiter.api.Test;
 
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
 
class KvServerPoolTest {
 
    private static final String BUSY = "-ERR server busy";
 
    // Several clients connected AT THE SAME TIME must all be answered.
    // With the Day A server, the second client would hang and this test would time out.
    @Test
    void severalClientsAreServedAtTheSameTime() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0, 4)) {
            server.start();
 
            List<TestClient> clients = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    clients.add(new TestClient(server.getPort()));
                }
                // All four connections stay open while each one sends a command.
                for (int i = 0; i < 4; i++) {
                    assertEquals(":" + (i + 1), clients.get(i).send("INCR counter"));
                }
            } finally {
                for (TestClient c : clients) {
                    c.close();
                }
            }
        }
    }
 
    // When every pool thread is busy, the next client is told "server busy" and disconnected.
    @Test
    void clientsBeyondTheLimitAreRejected() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0, 2)) {
            server.start();
 
            try (TestClient a = new TestClient(server.getPort());
                 TestClient b = new TestClient(server.getPort())) {
                assertEquals("+OK", a.send("SET a 1"));
                assertEquals("+OK", b.send("SET b 2"));   // both pool threads are now occupied
 
                try (TestClient c = new TestClient(server.getPort())) {
                    // The client sends nothing: the server speaks first, then hangs up.
                    assertEquals(BUSY, c.in.readLine());
                    assertNull(c.in.readLine(), "server should close a rejected connection");
                }
            }
        }
    }
 
    // After a client disconnects, its thread goes back to the pool and a new client is accepted.
    @Test
    void aSlotIsFreedWhenAClientDisconnects() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0, 1)) {
            server.start();
 
            try (TestClient first = new TestClient(server.getPort())) {
                assertEquals("+OK", first.send("SET a 1"));
            }   // disconnects; the server thread notices and returns to the pool
 
            // The server needs a moment to notice the disconnect, so retry briefly.
            String reply = BUSY;
            for (int attempt = 0; attempt < 40 && BUSY.equals(reply); attempt++) {
                try (TestClient next = new TestClient(server.getPort())) {
                    reply = next.send("GET a");
                } catch (IOException e) {
                    reply = BUSY;     // a rejected connection can show up as a reset; treat as busy
                }
                if (BUSY.equals(reply)) {
                    Thread.sleep(50);
                }
            }
            assertEquals("1", reply);
        }
    }
 
    // End-to-end concurrency proof: many real clients incrementing one shared counter.
    @Test
    void concurrentClientsGetAnExactCount() throws Exception {
        int clients = 10;
        int perClient = 200;
 
        // maxClients is higher than the number of clients, so the final check below
        // can't be turned away while worker threads are still returning to the pool.
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0, 12)) {
            server.start();
 
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
 
            for (int i = 0; i < clients; i++) {
                Thread t = new Thread(() -> {
                    try (TestClient c = new TestClient(server.getPort())) {
                        for (int n = 0; n < perClient; n++) {
                            String reply = c.send("INCR counter");
                            if (!reply.startsWith(":")) {
                                throw new AssertionError("unexpected reply: " + reply);
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                threads.add(t);
            }
 
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join(30_000);
 
            assertNull(failure.get(), () -> "a client thread failed: " + failure.get());
 
            try (TestClient check = new TestClient(server.getPort())) {
                assertEquals(String.valueOf(clients * perClient), check.send("GET counter"));
            }
        }
    }
}