package com.redislite;
 
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
 
/**
 * Day B version: each client is served on a thread from a bounded pool.
 * When every thread is busy, a new connection is told "-ERR server busy" and closed.
 */
public class KvServer implements AutoCloseable {
 
    private static final int DEFAULT_MAX_CLIENTS = 32;
 
    private final CommandProcessor processor;
    private final int port;
    private final int maxClients;
 
    // Sockets of clients currently being served, so close() can force their blocked reads to end.
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
 
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor pool;
 
    public KvServer(CommandProcessor processor, int port) {
        this(processor, port, DEFAULT_MAX_CLIENTS);
    }
 
    public KvServer(CommandProcessor processor, int port, int maxClients) {
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive");
        }
        this.processor = processor;
        this.port = port;
        this.maxClients = maxClients;
    }
 
    /** Binds the port and starts accepting on a background thread. Returns immediately. */
    public void start() throws IOException {
        // core == max and a SynchronousQueue (which holds nothing): a connection either gets a
        // free thread immediately or the pool throws RejectedExecutionException. No hidden queue.
        pool = new ThreadPoolExecutor(
                maxClients, maxClients,
                0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                daemonThreads());
 
        // Loopback only: there is no authentication, so don't expose the store to the network.
        serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
 
        acceptThread = new Thread(this::acceptLoop, "kv-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }
 
    /** The port actually bound. Useful when constructed with port 0 (the OS picks a free one). */
    public int getPort() {
        return serverSocket.getLocalPort();
    }
 
    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                try {
                    pool.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                    if (serverSocket.isClosed()) {
                        closeQuietly(client);          // shutting down, not really "busy"
                    } else {
                        rejectBusy(client);
                    }
                }
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;                             // normal shutdown: close() was called
                }
                System.err.println("accept failed: " + e.getMessage());
            }
        }
    }
 
    /** Runs on a pool thread for the whole life of one client connection. */
    private void serve(Socket client) {
        activeClients.add(client);
        try {
            new ClientHandler(client, processor).run();
        } finally {
            activeClients.remove(client);
        }
    }
 
    private void rejectBusy(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(1000);
            OutputStream out = s.getOutputStream();
            out.write("-ERR server busy\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // the client may already be gone; nothing more to do
        }
    }
 
    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
 
    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "kv-client-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
 
    /** Blocks until the accept thread ends. Lets main() keep the JVM alive. */
    public void join() throws InterruptedException {
        acceptThread.join();
    }
 
    @Override
    public void close() {
        try {
            if (serverSocket != null) {
                serverSocket.close();       // makes accept() throw, which ends the accept loop
            }
        } catch (IOException ignored) {
            // nothing useful to do while shutting down
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        // Threads blocked reading from a client are not woken by shutdownNow(); closing their
        // sockets is what makes those reads fail so the threads can finish.
        for (Socket s : activeClients) {
            closeQuietly(s);
        }
    }
}