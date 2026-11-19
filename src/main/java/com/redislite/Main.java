package com.redislite;

import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws Exception {
        RedisLiteApp.Config config = parse(args);
        RedisLiteApp app = new RedisLiteApp(config);

        // Runs on Ctrl+C / normal JVM exit. Registered BEFORE start() so a half-started
        // app is also cleaned up (close() copes with parts that never started).
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));

        app.start();
        System.out.println("redis-lite listening on 127.0.0.1:" + app.getPort());
        app.join();
    }

    private static RedisLiteApp.Config parse(String[] args) {
        RedisLiteApp.Config d = RedisLiteApp.Config.defaults();
        int port = d.port();
        int maxClients = d.maxClients();
        int idleTimeoutMs = d.idleTimeoutMs();
        int maxLineBytes = d.maxLineBytes();
        String snapshotFile = d.snapshotFile().toString();
        long snapshotInterval = d.snapshotIntervalSeconds();

        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 3) {
                usage("bad argument: " + arg);
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            try {
                switch (key) {
                    case "port" -> port = Integer.parseInt(value);
                    case "max-clients" -> maxClients = Integer.parseInt(value);
                    case "idle-timeout-ms" -> idleTimeoutMs = Integer.parseInt(value);
                    case "max-line-bytes" -> maxLineBytes = Integer.parseInt(value);
                    case "snapshot-file" -> snapshotFile = value;
                    case "snapshot-interval" -> snapshotInterval = Long.parseLong(value);
                    default -> usage("unknown option: --" + key);
                }
            } catch (NumberFormatException e) {
                usage("not a number: " + arg);
            }
        }

        Path file = snapshotFile.equalsIgnoreCase("none") ? null : Path.of(snapshotFile);
        return new RedisLiteApp.Config(port, maxClients, idleTimeoutMs, maxLineBytes, file, snapshotInterval);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: java -cp target/classes com.redislite.Main [options]");
        System.err.println("  --port=6380            TCP port (0 = pick a free one)");
        System.err.println("  --max-clients=32       connections served at once; more are told 'server busy'");
        System.err.println("  --idle-timeout-ms=60000  disconnect clients silent for this long");
        System.err.println("  --max-line-bytes=8192  longest accepted command line");
        System.err.println("  --snapshot-file=dump.snapshot   where to save data ('none' turns persistence off)");
        System.err.println("  --snapshot-interval=60 seconds between automatic saves (0 = only on shutdown)");
        System.exit(2);
    }
}