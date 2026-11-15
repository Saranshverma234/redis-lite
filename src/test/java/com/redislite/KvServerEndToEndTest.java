package com.redislite;
 
import org.junit.jupiter.api.Test;
 
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
 
class KvServerEndToEndTest {
 
    private static final String BUSY = "-ERR server busy";
 
    private static KvServer newServer(InMemoryStore store, int maxClients) {
        return new KvServer(new CommandProcessor(store), 0, maxClients);
    }
 
    /** Fails unless the reply is non-null and matches the regex. */
    private static void expect(String reply, String regex) {
        if (reply == null) {
            throw new AssertionError("connection was closed unexpectedly");
        }
        if (!reply.matches(regex)) {
            throw new AssertionError("reply '" + reply + "' does not match " + regex);
        }
    }
 
    // Several commands arrive in one write; replies must come back in the same order.
    @Test
    void pipelinedCommandsAreAnsweredInOrder() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 4)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                c.sendPartial("SET a 1\r\nGET a\r\nINCR n\r\nINCR n\r\nGET nothing\r\n");
 
                assertEquals("+OK", c.in.readLine());
                assertEquals("1", c.in.readLine());
                assertEquals(":1", c.in.readLine());
                assertEquals(":2", c.in.readLine());
                assertEquals("$-1", c.in.readLine());
            }
        }
    }
 
    // Non-ASCII text must survive the round trip (the server reads and writes UTF-8 explicitly).
    @Test
    void unicodeValuesSurviveTheRoundTrip() throws Exception {
        String value = "h\u00e9llo-\u4e16\u754c-\u2713";   // escapes, so the source file's encoding can't matter
 
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 4)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                assertEquals("+OK", c.send("SET greeting " + value));
                assertEquals(value, c.send("GET greeting"));
            }
        }
    }
 
    // Connecting and disconnecting many times must not use up the pool (no thread or socket leaks).
    @Test
    void manyShortConnectionsInSequenceAreAllServed() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = newServer(store, 2)) {
            server.start();
 
            for (int i = 0; i < 300; i++) {
                assertEquals("+OK", roundTripWithRetry(server.getPort(), "SET k" + i + " v"));
            }
            assertEquals("v", roundTripWithRetry(server.getPort(), "GET k299"));
        }
    }
 
    /** A freed pool slot takes a moment to reappear, so retry while the server says "busy". */
    private static String roundTripWithRetry(int port, String command) throws Exception {
        String reply = BUSY;
        for (int attempt = 0; attempt < 100 && BUSY.equals(reply); attempt++) {
            try (TestClient c = new TestClient(port)) {
                reply = c.send(command);
            } catch (IOException e) {
                reply = BUSY;       // a rejected connection can show up as a reset
            }
            if (BUSY.equals(reply)) {
                Thread.sleep(20);
            }
        }
        return reply;
    }
 
    // Random mixed commands from many real clients, with the sweeper running as well.
    // Every reply must be well-formed, and nothing may report an internal error.
    @Test
    void mixedStressThroughTheServer() throws Exception {
        int clients = 16;
        int opsPerClient = 2000;
 
        try (InMemoryStore store = new InMemoryStore(50);          // sweeper racing the clients
             KvServer server = newServer(store, 20)) {
            server.start();
 
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
 
            for (int t = 0; t < clients; t++) {
                threads.add(new Thread(() -> {
                    try (TestClient c = new TestClient(server.getPort())) {
                        ThreadLocalRandom r = ThreadLocalRandom.current();
                        String[] keys = {"c0", "c1", "c2"};
 
                        for (int i = 0; i < opsPerClient; i++) {
                            String key = keys[r.nextInt(keys.length)];
                            switch (r.nextInt(7)) {
                                case 0 -> expect(c.send("SET " + key + " " + r.nextInt(100)), "\\+OK");
                                case 1 -> expect(c.send("GET " + key), "\\$-1|-?\\d+");
                                case 2 -> expect(c.send("DEL " + key), ":[01]");
                                case 3 -> expect(c.send("EXPIRE " + key + " " + (1 + r.nextInt(3))), ":[01]");
                                case 4 -> expect(c.send("PERSIST " + key), ":[01]");
                                case 5 -> expect(c.send("TTL " + key), ":(-2|-1|[0-3])");
                                case 6 -> expect(c.send("INCR " + key), ":-?\\d+");
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }));
            }
 
            for (Thread t : threads) t.start();
            for (Thread t : threads) {
                t.join(60_000);
                assertFalse(t.isAlive(), "a client thread did not finish in time");
            }
 
            assertNull(failure.get(), () -> "a client failed: " + failure.get());
        }
    }
 
    // close() must not leave connected clients hanging, and must stop accepting new ones.
    @Test
    void closingTheServerDisconnectsClientsAndRefusesNewOnes() throws Exception {
        InMemoryStore store = new InMemoryStore();
        KvServer server = newServer(store, 4);
        server.start();
        int port = server.getPort();
 
        try (TestClient c = new TestClient(port)) {
            assertEquals("+OK", c.send("SET a 1"));
 
            server.close();
 
            try {
                c.in.readLine();      // end of stream (null) or a reset are both fine...
            } catch (SocketTimeoutException e) {
                fail("the connected client was left hanging after close()");   // ...a timeout is not
            } catch (IOException ignored) {
                // connection reset: also a disconnect
            }
        }
 
        assertThrows(IOException.class, () -> new TestClient(port));   // nothing is listening any more
        store.close();
    }
}