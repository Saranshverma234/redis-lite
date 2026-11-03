package com.redislite;
 
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
 
/** A tiny blocking client for tests: send one line, read one reply line. */
class TestClient implements AutoCloseable {
 
    final Socket socket;
    final BufferedReader in;
    private final BufferedWriter out;
 
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