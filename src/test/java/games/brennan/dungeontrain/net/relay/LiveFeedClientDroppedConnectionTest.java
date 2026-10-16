package games.brennan.dungeontrain.net.relay;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Result;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.SignedPut;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real upload path against a local socket that hangs up without answering — what a stale
 * keep-alive connection to storage looks like ("header parser received no bytes").
 */
class LiveFeedClientDroppedConnectionTest {

    /** Serves {@code drops} connections by reading the request and closing, then answers 200. */
    private static Thread serve(ServerSocket server, int drops, AtomicInteger connections) {
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    int n = connections.incrementAndGet();
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[8192];
                    in.read(buf); // the request line, headers and small body
                    if (n <= drops) continue; // hang up, no bytes
                    OutputStream out = s.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception e) {
                    return;
                }
            }
        }, "dropped-connection-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Result put(ServerSocket server) throws Exception {
        SignedPut target = new SignedPut("http://127.0.0.1:" + server.getLocalPort() + "/main/s/live.m3u8",
            Map.of("Content-Type", "application/vnd.apple.mpegurl"), 0L);
        return LiveFeedClient.upload(target, "#EXTM3U\n").get(30, TimeUnit.SECONDS);
    }

    @Test
    void hangUpIsRetriedOnAFreshConnection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            serve(server, 1, connections);
            Result r = put(server);
            assertEquals(200, r.status(), String.valueOf(r.error()));
            assertEquals(2, connections.get());
        }
    }

    @Test
    void givesUpAfterTwoRetries() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            serve(server, 10, connections);
            assertEquals(0, put(server).status());
            assertEquals(3, connections.get());
        }
    }
}
