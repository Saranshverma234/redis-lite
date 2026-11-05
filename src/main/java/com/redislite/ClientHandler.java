package com.redislite;
 
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
 
/** Talks to one connected client: read a line, run it, write the reply. */
class ClientHandler implements Runnable {
 
    /** Thrown when a client sends a line longer than the configured limit. */
    static class LineTooLongException extends IOException {}
 
    private final Socket socket;
    private final CommandProcessor processor;
    private final int idleTimeoutMs;
    private final int maxLineBytes;
 
    ClientHandler(Socket socket, CommandProcessor processor, int idleTimeoutMs, int maxLineBytes) {
        this.socket = socket;
        this.processor = processor;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxLineBytes = maxLineBytes;
    }
 
    @Override
    public void run() {
        // try-with-resources closes the socket however we leave this block
        try (Socket s = socket) {
            s.setSoTimeout(idleTimeoutMs);   // a read that waits this long throws SocketTimeoutException
            s.setTcpNoDelay(true);           // don't let Nagle's algorithm delay small replies
 
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
 
            try {
                while (true) {
                    String line = readLine(in, maxLineBytes);
                    if (line == null) {
                        break;                                   // client closed the connection
                    }
                    if (line.trim().equalsIgnoreCase("QUIT")) {
                        send(out, Response.ok());
                        break;
                    }
                    send(out, processor.handle(line));
                }
            } catch (LineTooLongException e) {
                sendQuietly(out, Response.error("line too long"));
                drain(s, in);                                    // see drain() for why
            } catch (SocketTimeoutException e) {
                sendQuietly(out, Response.error("idle timeout"));
            }
        } catch (IOException e) {
            // client vanished mid-conversation: normal for network code, nothing to do
        }
    }
 
    /**
     * Reads up to the next '\n'. Returns the line without its terminator (a trailing '\r' is
     * dropped too), or null if the client closed the connection. Throws LineTooLongException
     * instead of buffering without limit.
     */
    static String readLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            if (buf.size() >= maxBytes && !(b == '\r' && buf.size() == maxBytes)) {
    throw new LineTooLongException();
}
            buf.write(b);
        }
        return null;   // end of stream (a half-written last line is discarded)
    }
 
    private static void send(OutputStream out, String reply) throws IOException {
        out.write((reply + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();      // without this the reply sits in the buffer and the client waits forever
    }
 
    private static void sendQuietly(OutputStream out, String reply) {
        try {
            send(out, reply);
        } catch (IOException ignored) {
            // the client may already be gone
        }
    }
 
    /**
     * After we stop reading a too-long line, the client may still be sending the rest of it.
     * Closing a socket that has unread incoming data makes TCP send a reset, which can wipe out
     * the error message before the client reads it. So read and discard briefly (bounded in both
     * time and size, so a client can't use this to hold the thread) before closing.
     */
    private static void drain(Socket s, InputStream in) {
        try {
            s.setSoTimeout(200);
            byte[] junk = new byte[4096];
            long total = 0;
            int n;
            while (total < 1_000_000 && (n = in.read(junk)) != -1) {
                total += n;
            }
        } catch (IOException ignored) {
            // timeout or reset: we're closing anyway
        }
    }
}