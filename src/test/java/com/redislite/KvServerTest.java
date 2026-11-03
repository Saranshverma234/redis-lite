package com.redislite;
 
import org.junit.jupiter.api.Test;
 
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
class KvServerTest {
 
    /** A tiny test client: sends one line, returns the one-line reply. */
    private static class TestClient implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;
 
        TestClient(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(5000);   // a hung server fails the test instead of hanging it
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }
 
        String send(String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
            return in.readLine();
        }
 
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
 
    @Test
    void commandsWorkOverARealSocket() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0)) {
            server.start();
 
            try (TestClient c = new TestClient(server.getPort())) {
                assertEquals("+OK", c.send("SET a 1"));
                assertEquals("1", c.send("GET a"));
                assertEquals("$-1", c.send("GET missing"));
                assertTrue(c.send("FLY away").startsWith("-ERR"));
                assertEquals("+OK", c.send("QUIT"));
                assertNull(c.in.readLine(), "server should close the connection after QUIT");
            }
        }
    }
 
    @Test
    void aSecondClientIsServedAfterTheFirstDisconnects() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0)) {
            server.start();
 
            try (TestClient first = new TestClient(server.getPort())) {
                assertEquals("+OK", first.send("SET shared 42"));
            }   // first client disconnects here
 
            try (TestClient second = new TestClient(server.getPort())) {
                assertEquals("42", second.send("GET shared"));   // the store is shared across clients
            }
        }
    }
}