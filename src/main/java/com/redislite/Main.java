package com.redislite;
 
public class Main {
 
    public static void main(String[] args) throws Exception {
        // Default 6380 so it never clashes with a real Redis on 6379.
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 6380;
 
        InMemoryStore store = new InMemoryStore(1000);          // sweeper runs every second
        CommandProcessor processor = new CommandProcessor(store);
        KvServer server = new KvServer(processor, port);
 
        // Runs on Ctrl+C: stop accepting and stop the sweeper thread.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            store.close();
        }));
 
        server.start();
        System.out.println("redis-lite listening on 127.0.0.1:" + server.getPort());
        server.join();      // keep main alive; the accept thread is a daemon
    }
}