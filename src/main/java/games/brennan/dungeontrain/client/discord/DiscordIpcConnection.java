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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One connection to the Discord desktop app's local IPC endpoint, speaking its Rich Presence protocol:
 * a frame is a little-endian int32 opcode, a little-endian int32 length, then that many bytes of JSON.
 * Handshake is opcode 0 with {@code {v:1, client_id}}; activity updates are opcode 1 {@code SET_ACTIVITY}.
 *
 * <p>Endpoints: {@code \\.\pipe\discord-ipc-N} on Windows; elsewhere a unix socket {@code discord-ipc-N}
 * in {@code $XDG_RUNTIME_DIR}, {@code $TMPDIR}, {@code $TMP}, {@code $TEMP} or {@code /tmp}: Discord's
 * documented order, except that Discord stops at the first variable that is set and we try them all, plus
 * the Flatpak and Snap subfolders of each (community convention, not in Discord's docs). Nothing else is
 * ever contacted. Only the {@link DiscordRichPresence} worker thread uses this class.</p>
 *
 * <p>On unix a socket is used only when it belongs to the user the game runs as, so a socket another local
 * user left in a shared folder such as {@code /tmp} is skipped, and every connect, write and reply has a
 * {@link #REPLY_TIMEOUT_MILLIS} deadline, so an endpoint that never answers is dropped and retried later.
 * Known limit on Windows: plain Java can neither check who serves a named pipe nor time out a pipe read, so
 * a pipe that never answers parks the status thread (the game itself is unaffected).</p>
 */
final class DiscordIpcConnection implements Closeable {

    static final int OP_HANDSHAKE = 0;
    static final int OP_FRAME = 1;
    static final int OP_CLOSE = 2;

    private static final int MAX_PIPES = 10;
    /** Discord's replies are small; anything bigger means we are not talking to Discord. */
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final String[] UNIX_SUBDIRS = {"", "app/com.discordapp.Discord/", "snap.discord/"};
    /** How long Discord gets to accept, take a frame, or answer one before the endpoint is dropped. */
    static final long REPLY_TIMEOUT_MILLIS = 5_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordIpcConnection.class);
    /** Who the game runs as, worked out once; see {@link #currentOwner()}. */
    private static volatile String currentOwner;

    private final Transport transport;

    private DiscordIpcConnection(Transport transport) {
        this.transport = transport;
    }

    /** Connects and completes the handshake, or throws. The caller closes it. */
    static DiscordIpcConnection open(String applicationId) throws IOException {
        return handshake(connectAny(), applicationId);
    }

    /** Test seam: {@link #open} against one unix socket with its own deadline. */
    static DiscordIpcConnection openUnix(String applicationId, String path, long timeoutMillis) throws IOException {
        return handshake(new UnixTransport(path, timeoutMillis), applicationId);
    }

    private static DiscordIpcConnection handshake(Transport transport, String applicationId) throws IOException {
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
        if (snapshot.imageKey() != null) {
            assets.addProperty("small_image", snapshot.imageKey());
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
                return windows ? new PipeTransport(path) : new UnixTransport(path, REPLY_TIMEOUT_MILLIS);
            } catch (IOException | RuntimeException ignored) {
                // Try the next pipe; Discord takes the lowest free index, other clients may hold the rest.
            }
        }
        throw new IOException("Discord is not running (no IPC endpoint found)");
    }

    /** Every endpoint to try, in order. Unix paths are listed only when a socket we own is there. */
    static List<String> candidatePaths(boolean windows, Map<String, String> env) {
        return candidatePaths(windows, env, windows ? null : currentOwner());
    }

    /** As above, with the owner a unix socket must have; null lists no unix socket at all. */
    static List<String> candidatePaths(boolean windows, Map<String, String> env, String expectedOwner) {
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
                    if (isOwnSocket(Path.of(path), expectedOwner)) paths.add(path);
                }
            }
        }
        return paths;
    }

    /**
     * True when {@code path} is itself a socket (not a symlink to one) owned by {@code expectedOwner}. Any
     * error reading it counts as no: a socket we cannot vouch for is never used.
     */
    static boolean isOwnSocket(Path path, String expectedOwner) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            PosixFileAttributes attributes =
                    Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String owner = attributes.owner().getName();
            if (attributes.isOther() && expectedOwner != null && expectedOwner.equals(owner)) return true;
            LOGGER.debug("[DungeonTrain] Discord status: skipping {} (owner {}, socket {}).",
                    path, owner, attributes.isOther());
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Discord status: skipping {} ({}).", path, e.toString());
        }
        return false;
    }

    /**
     * The user the game runs as: the owner of a file this process creates, so it is the same lookup the socket
     * check uses, whatever {@code user.name} says. Null (no unix socket is trusted) if that cannot be read.
     */
    static String currentOwner() {
        String owner = currentOwner;
        if (owner != null) return owner;
        Path probe = null;
        try {
            probe = Files.createTempFile("dungeontrain-discord", ".probe");
            owner = Files.readAttributes(probe, PosixFileAttributes.class).owner().getName();
            currentOwner = owner;
            return owner;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Discord status: cannot tell who the game runs as ({}).", e.toString());
            return null;
        } finally {
            if (probe != null) {
                try { Files.deleteIfExists(probe); } catch (IOException ignored) { /* temp file, harmless */ }
            }
        }
    }

    private interface Transport {
        void write(byte[] bytes) throws IOException;

        byte[] read(int length) throws IOException;

        void close();
    }

    /** Non-blocking socket driven through a selector, so every step can give up at a deadline. */
    private static final class UnixTransport implements Transport {
        private final SocketChannel channel;
        private final Selector selector;
        private final long timeoutNanos;

        UnixTransport(String path, long timeoutMillis) throws IOException {
            this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            this.channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            Selector opened = null;
            try {
                opened = Selector.open();
                channel.configureBlocking(false);
                long deadline = System.nanoTime() + timeoutNanos;
                if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                    while (!channel.finishConnect()) await(opened, SelectionKey.OP_CONNECT, deadline);
                }
            } catch (IOException | RuntimeException e) {
                closeQuietly(channel, opened);
                throw e;
            }
            this.selector = opened;
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long deadline = System.nanoTime() + timeoutNanos;
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) await(selector, SelectionKey.OP_WRITE, deadline);
            }
        }

        @Override
        public byte[] read(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long deadline = System.nanoTime() + timeoutNanos;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) throw new IOException("Discord IPC socket closed");
                if (read == 0) await(selector, SelectionKey.OP_READ, deadline);
            }
            return buffer.array();
        }

        /** Waits until {@code op} may be ready, or throws once {@code deadline} has passed. */
        private void await(Selector on, int op, long deadline) throws IOException {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new IOException("Discord IPC timed out");
            channel.register(on, op);
            on.select(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
            on.selectedKeys().clear();
        }

        @Override
        public void close() {
            closeQuietly(channel, selector);
        }

        private static void closeQuietly(SocketChannel channel, Selector selector) {
            try { channel.close(); } catch (IOException ignored) { /* already gone */ }
            if (selector != null) {
                try { selector.close(); } catch (IOException ignored) { /* already gone */ }
            }
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
