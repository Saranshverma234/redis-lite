package com.redislite;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BindAddressTest {

    @Test
    void listeningOnAllInterfacesStillAcceptsLoopbackClients() throws Exception {
        try (InMemoryStore store = new InMemoryStore();
             KvServer server = new KvServer(new CommandProcessor(store), 0, 4, 60_000, 8192,
                     InetAddress.getByName("0.0.0.0"))) {
            server.start();

            try (TestClient c = new TestClient(server.getPort())) {
                assertEquals("+OK", c.send("SET a 1"));
                assertEquals("1", c.send("GET a"));
            }
        }
    }

    @Test
    void aNullBindAddressIsRejected() {
        InMemoryStore store = new InMemoryStore();
        assertThrows(IllegalArgumentException.class,
                () -> new KvServer(new CommandProcessor(store), 0, 4, 60_000, 8192, null));
    }
}