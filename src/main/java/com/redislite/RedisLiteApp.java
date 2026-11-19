package com.redislite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the lifecycle of the whole application: store, snapshots and TCP server.
 * Main only parses arguments and calls this, so tests can start and stop the real thing.
 *
 * Startup order:  store -> load snapshot -> start periodic saves -> start accepting clients
 * Shutdown order: stop clients -> final snapshot -> stop the store's sweeper
 */
public final class RedisLiteApp implements AutoCloseable {

    /** snapshotFile == null turns persistence off. snapshotIntervalSeconds == 0 means "no periodic saves". */
    public record Config(int port, int maxClients, int idleTimeoutMs, int maxLineBytes,
                         Path snapshotFile, long snapshotIntervalSeconds) {

        public static Config defaults() {
            // 6380 so it never clashes with a real Redis on 6379
            return new Config(6380, 32, 60_000, 8192, Path.of("dump.snapshot"), 60);
        }
    }

    private final Config config;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // volatile: start() runs on the main thread, close() may run on the JVM's shutdown-hook thread
    private volatile InMemoryStore store;
    private volatile SnapshotManager snapshots;
    private volatile KvServer server;

    public RedisLiteApp(Config config) {
        this.config = config;
    }

    public void start() throws IOException {
        InMemoryStore s = new InMemoryStore(1000);          // background expiry sweeper every second
        store = s;

        if (config.snapshotFile() != null) {
            SnapshotManager m = new SnapshotManager(s, config.snapshotFile());
            int restored = m.loadOrQuarantine();            // throws if the file exists but cannot be read
            snapshots = m;                                  // assigned only AFTER a successful load: if the
                                                            // load threw, close() must NOT save an empty
                                                            // store over a snapshot we could not read
            System.out.println("restored " + restored + " keys from " + config.snapshotFile());

            if (config.snapshotIntervalSeconds() > 0) {
                m.start(config.snapshotIntervalSeconds() * 1000);
            }
        }

        KvServer srv = new KvServer(new CommandProcessor(s), config.port(),
                config.maxClients(), config.idleTimeoutMs(), config.maxLineBytes());
        srv.start();
        server = srv;
    }

    public int getPort() {
        return server.getPort();
    }

    /** Blocks until the server stops. Keeps main() alive. */
    public void join() throws InterruptedException {
        server.join();
    }

    /** Safe to call more than once and safe to call after a failed start(). */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        KvServer srv = server;
        if (srv != null) {
            srv.close();                  // 1. no more clients, so no more writes
        }
        SnapshotManager m = snapshots;
        if (m != null) {
            m.close();                    // 2. stop periodic saves, then write the final snapshot
        }
        InMemoryStore s = store;
        if (s != null) {
            s.close();                    // 3. stop the sweeper
        }
    }
}
