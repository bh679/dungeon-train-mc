package games.brennan.dungeontrain.client.discord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unix side of the Discord IPC client against real local sockets: who it trusts, and its deadline. */
@DisabledOnOs(OS.WINDOWS)
class DiscordIpcConnectionTest {

    private Path root;
    private ServerSocketChannel server;

    @BeforeEach
    void setUp() throws IOException {
        // Short path on purpose: unix socket paths are capped near 104 characters on macOS.
        root = Files.createTempDirectory("dti");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private Path bind(String name) throws IOException {
        Path path = root.resolve(name);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(path));
        return path;
    }

    private List<String> candidatesUnderRoot(String expectedOwner) {
        return DiscordIpcConnection.candidatePaths(false, Map.of("XDG_RUNTIME_DIR", root.toString()), expectedOwner)
                .stream().filter(p -> p.startsWith(root.toString())).toList();
    }

    @Test
    @DisplayName("Only a socket owned by the game's own user is a candidate")
    void ownerFilter() throws IOException {
        Files.writeString(root.resolve("discord-ipc-0"), "not a socket");
        Path socket = bind("discord-ipc-1");
        String me = DiscordIpcConnection.currentOwner();
        assertNotNull(me, "the game's own user should be readable");

        assertEquals(List.of(socket.toString()), candidatesUnderRoot(me));
        assertEquals(List.of(), candidatesUnderRoot("definitely-not-a-user"));
        assertEquals(List.of(), candidatesUnderRoot(null));
    }

    @Test
    @DisplayName("A symlink to our own socket is not followed")
    void symlinkSkipped() throws IOException {
        Path socket = bind("real-ipc");
        Files.createSymbolicLink(root.resolve("discord-ipc-0"), socket);
        assertEquals(List.of(), candidatesUnderRoot(DiscordIpcConnection.currentOwner()));
    }

    @Test
    @DisplayName("An endpoint that never answers is dropped at the deadline")
    void silentEndpointTimesOut() throws IOException {
        Path socket = bind("discord-ipc-0");
        // Connections queue in the backlog without accept(); nobody ever replies.
        IOException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class,
                () -> DiscordIpcConnection.openUnix("1", socket.toString(), 300)));
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
    }

    @Test
    @DisplayName("Handshake and one activity update round-trip with a well-behaved endpoint")
    void happyPath() throws Exception {
        Path socket = bind("discord-ipc-0");
        Thread fake = new Thread(() -> {
            try (SocketChannel client = server.accept()) {
                readFrame(client);
                writeFrame(client, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\",\"data\":{}}");
                readFrame(client);
                writeFrame(client, "{\"cmd\":\"SET_ACTIVITY\",\"evt\":null,\"data\":{}}");
            } catch (IOException ignored) {
                // The assertions below fail if the exchange did not complete.
            }
        }, "fake discord");
        fake.setDaemon(true);
        fake.start();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (DiscordIpcConnection connection = DiscordIpcConnection.openUnix("1", socket.toString(), 2_000)) {
                connection.setActivity(new PresenceSnapshot("overworld", "In the Overworld"));
            }
        });
        fake.join(2_000);
    }

    private static void readFrame(SocketChannel channel) throws IOException {
        ByteBuffer header = readFully(channel, 8).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt();
        readFully(channel, header.getInt());
    }

    private static ByteBuffer readFully(SocketChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("closed");
        }
        return buffer.flip();
    }

    private static void writeFrame(SocketChannel channel, String json) throws IOException {
        ByteBuffer frame = ByteBuffer.wrap(DiscordIpcConnection.encodeFrame(DiscordIpcConnection.OP_FRAME, json));
        while (frame.hasRemaining()) channel.write(frame);
    }
}
