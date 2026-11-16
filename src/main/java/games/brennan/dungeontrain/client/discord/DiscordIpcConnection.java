package games.brennan.dungeontrain.client.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One connection to the Discord desktop app's local IPC endpoint, speaking its Rich Presence protocol:
 * a frame is a little-endian int32 opcode, a little-endian int32 length, then that many bytes of JSON.
 * Handshake is opcode 0 with {@code {v:1, client_id}}; activity updates are opcode 1 {@code SET_ACTIVITY}.
 *
 * <p>Endpoints: {@code \\.\pipe\discord-ipc-N} on Windows; elsewhere a unix socket {@code discord-ipc-N}
 * in {@code $XDG_RUNTIME_DIR}, {@code $TMPDIR}, {@code $TMP}, {@code $TEMP} or {@code /tmp}, and in the
 * Flatpak and Snap subfolders of each. Nothing else is ever contacted. Blocking, so only the
 * {@link DiscordRichPresence} worker thread uses it.</p>
 */
final class DiscordIpcConnection implements Closeable {

    static final int OP_HANDSHAKE = 0;
    static final int OP_FRAME = 1;
    static final int OP_CLOSE = 2;

    private static final int MAX_PIPES = 10;
    /** Discord's replies are small; anything bigger means we are not talking to Discord. */
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final String[] UNIX_SUBDIRS = {"", "app/com.discordapp.Discord/", "snap.discord/"};

    private final Transport transport;

    private DiscordIpcConnection(Transport transport) {
        this.transport = transport;
    }

    /** Connects and completes the handshake, or throws. The caller closes it. */
    static DiscordIpcConnection open(String applicationId) throws IOException {
        Transport transport = connectAny();
        DiscordIpcConnection connection = new DiscordIpcConnection(transport);
        try {
            JsonObject hello = new JsonObject();
            hello.addProperty("v", 1);
            hello.addProperty("client_id", applicationId);
            connection.send(OP_HANDSHAKE, hello);
            connection.expectReply();
            return connection;
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /** Shows {@code snapshot} on the player's profile, or clears it when {@code snapshot} is null. */
    void setActivity(PresenceSnapshot snapshot) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (snapshot != null) {
            args.add("activity", activityJson(snapshot));
        }
        JsonObject command = new JsonObject();
        command.addProperty("cmd", "SET_ACTIVITY");
        command.add("args", args);
        command.addProperty("nonce", UUID.randomUUID().toString());
        send(OP_FRAME, command);
        expectReply();
    }

    static JsonObject activityJson(PresenceSnapshot snapshot) {
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "logo");
        assets.addProperty("large_text", "Dungeon Train");
        if (snapshot.place() != PresenceSnapshot.Place.MENU) {
            assets.addProperty("small_image", snapshot.place().imageKey());
            assets.addProperty("small_text", snapshot.details());
        }
        JsonObject activity = new JsonObject();
        activity.addProperty("details", snapshot.details());
        activity.add("assets", assets);
        return activity;
    }

    /** {@code opcode}, {@code length}, payload — both ints little-endian, as Discord expects. */
    static byte[] encodeFrame(int opcode, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode).putInt(payload.length).put(payload);
        return buffer.array();
    }

    private void send(int opcode, JsonObject json) throws IOException {
        transport.write(encodeFrame(opcode, json.toString()));
    }

    /** Reads one reply; Discord answers every frame. Opcode 2 means it refused us (bad id, etc.). */
    private void expectReply() throws IOException {
        ByteBuffer header = ByteBuffer.wrap(transport.read(8)).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Discord IPC reply length out of range: " + length);
        }
        String body = new String(transport.read(length), StandardCharsets.UTF_8);
        if (opcode == OP_CLOSE) {
            throw new IOException("Discord closed the connection: " + body);
        }
        if (isErrorReply(body)) {
            throw new IOException("Discord rejected the command: " + body);
        }
    }

    /** True for {@code "evt":"ERROR"}. A normal command reply carries {@code "evt":null}, so null is not an error. */
    static boolean isErrorReply(String body) {
        JsonObject reply = JsonParser.parseString(body).getAsJsonObject();
        JsonElement evt = reply.get("evt");
        return evt != null && evt.isJsonPrimitive() && "ERROR".equals(evt.getAsString());
    }

    @Override
    public void close() {
        transport.close();
    }

    // ------------------------------------------------------------------- endpoints

    private static Transport connectAny() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        for (String path : candidatePaths(windows, System.getenv())) {
            try {
                return windows ? new PipeTransport(path) : new UnixTransport(path);
            } catch (IOException | RuntimeException ignored) {
                // Try the next pipe; Discord takes the lowest free index, other clients may hold the rest.
            }
        }
        throw new IOException("Discord is not running (no IPC endpoint found)");
    }

    /** Every endpoint to try, in order. Unix paths are listed only when the socket file exists. */
    static List<String> candidatePaths(boolean windows, java.util.Map<String, String> env) {
        List<String> paths = new ArrayList<>();
        if (windows) {
            for (int i = 0; i < MAX_PIPES; i++) paths.add("\\\\.\\pipe\\discord-ipc-" + i);
            return paths;
        }
        List<String> roots = new ArrayList<>();
        for (String var : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String value = env.get(var);
            if (value != null && !value.isBlank() && !roots.contains(value)) roots.add(value);
        }
        if (!roots.contains("/tmp")) roots.add("/tmp");
        for (String root : roots) {
            String base = root.endsWith("/") ? root : root + "/";
            for (String sub : UNIX_SUBDIRS) {
                for (int i = 0; i < MAX_PIPES; i++) {
                    String path = base + sub + "discord-ipc-" + i;
                    if (Files.exists(Path.of(path))) paths.add(path);
                }
            }
        }
        return paths;
    }

    private interface Transport {
        void write(byte[] bytes) throws IOException;

        byte[] read(int length) throws IOException;

        void close();
    }

    private static final class UnixTransport implements Transport {
        private final SocketChannel channel;

        UnixTransport(String path) throws IOException {
            this.channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(UnixDomainSocketAddress.of(path));
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        @Override
        public byte[] read(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new IOException("Discord IPC socket closed");
            }
            return buffer.array();
        }

        @Override
        public void close() {
            try { channel.close(); } catch (IOException ignored) { /* already gone */ }
        }
    }

    private static final class PipeTransport implements Transport {
        private final RandomAccessFile pipe;

        PipeTransport(String path) throws IOException {
            this.pipe = new RandomAccessFile(new File(path), "rw");
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            pipe.write(bytes);
        }

        @Override
        public byte[] read(int length) throws IOException {
            byte[] bytes = new byte[length];
            pipe.readFully(bytes);
            return bytes;
        }

        @Override
        public void close() {
            try { pipe.close(); } catch (IOException ignored) { /* already gone */ }
        }
    }
}
