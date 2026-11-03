package com.redislite;
 
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
 
/** Talks to one connected client: read a line, run it, write the reply. */
class ClientHandler implements Runnable {
 
    private final Socket socket;
    private final CommandProcessor processor;
 
    ClientHandler(Socket socket, CommandProcessor processor) {
        this.socket = socket;
        this.processor = processor;
    }
 
    @Override
    public void run() {
        // try-with-resources closes the streams and the socket however we leave this block
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
 
            String line;
            while ((line = in.readLine()) != null) {      // null means the client closed the connection
                if (line.trim().equalsIgnoreCase("QUIT")) {
                    send(out, Response.ok());
                    break;                                 // closing the socket ends the session
                }
                send(out, processor.handle(line));
            }
        } catch (IOException e) {
            // client vanished mid-conversation: normal for network code, nothing to do
        }
    }
 
    private static void send(BufferedWriter out, String reply) throws IOException {
        out.write(reply);
        out.write("\r\n");
        out.flush();      // without this the reply sits in the buffer and the client waits forever
    }
}