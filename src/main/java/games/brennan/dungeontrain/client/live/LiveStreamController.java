package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.LiveStreamEndedPacket;
import games.brennan.dungeontrain.net.LiveStreamPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Claim;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The streamer's side of the Live Feed, on the client that wears the headpiece.
 *
 * <pre>
 * IDLE --START--> WAIT_FFMPEG --ready--> CLAIMING --200--> STREAMING --stop--> IDLE
 * </pre>
 *
 * <p>Everything relay-facing happens here: claim the channel, then grab → encode → upload until
 * the server says stop, the relay says someone else took over (403 on presign), the player logs
 * out, the game closes, or ffmpeg dies. State changes run on the render thread; the encoder and
 * uploader have their own threads. Each stream has its own directory under
 * {@code <gamedir>/dungeontrain/live/}, deleted when it ends (and swept on the next start if a
 * crash left one behind).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveStreamController {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LiveStreamController INSTANCE = new LiveStreamController();

    public enum State { IDLE, WAIT_FFMPEG, CLAIMING, STREAMING, STOPPING }

    private volatile State state = State.IDLE;
    private int generation; // bumps on every start/stop so a late async reply cannot act on a newer stream
    @Nullable private LiveFrameGrabber grabber;
    @Nullable private LiveEncoder encoder;
    @Nullable private LiveUploader uploader;
    @Nullable private Claim claim;
    @Nullable private Path dir;
    private long lastGrabNs;
    private long streamStartedMs;
    private int waitTicks;

    private LiveStreamController() {}

    public static LiveStreamController get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    public boolean streaming() {
        return state == State.STREAMING;
    }

    /** Wall-clock start of the current broadcast; meaningful only while {@link #streaming()}. */
    public long streamStartedMs() {
        return streamStartedMs;
    }

    /** How many are watching this broadcast, or −1 when not streaming or not yet known. */
    public int viewerCount() {
        LiveUploader u = uploader;
        return state == State.STREAMING && u != null ? u.viewers() : -1;
    }

    /** The playlist viewers load for this client's broadcast, or null when not streaming. */
    @Nullable
    public String playlistUrl() {
        Claim c = claim;
        return state == State.STREAMING && c != null ? c.playlistUrl() : null;
    }

    // ---- server packets -----------------------------------------------------------------------

    public void onServerSaid(LiveStreamPacket packet) {
        switch (packet.action()) {
            case START -> start();
            case STOP_REPLACED -> stop(Component.translatable("chat.dungeontrain.live.replaced_here", packet.by()));
            case STOP_DIED -> stop(Component.translatable("chat.dungeontrain.live.ended_death"));
            case STOP_LEFT -> stop(null);
            case STOP_REMOVED -> stop(Component.translatable("chat.dungeontrain.live.removed"));
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private void start() {
        LOGGER.info("[DungeonTrain] live stream requested by the server (ffmpeg {})", FfmpegSupport.state());
        if (state != State.IDLE) stop(null);
        if (!LiveFeedClientConfig.streamingEnabled()) {
            say(Component.translatable("chat.dungeontrain.live.disabled").withStyle(ChatFormatting.GRAY));
            tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
            return;
        }
        generation++;
        state = State.WAIT_FFMPEG;
        waitTicks = 0;
        switch (FfmpegSupport.state()) {
            case OFF, FAILED -> {
                say(Component.translatable("chat.dungeontrain.live.no_ffmpeg").withStyle(ChatFormatting.RED));
                state = State.IDLE;
                tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
            }
            case DOWNLOADING -> say(Component.translatable("chat.dungeontrain.live.preparing").withStyle(ChatFormatting.GRAY));
            case READY -> claimChannel();
        }
    }

    /** Client tick: finish waiting for ffmpeg, and watch the encoder while streaming. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    private void tick() {
        if (state == State.WAIT_FFMPEG) {
            if (++waitTicks % 20 != 0) return;
            switch (FfmpegSupport.state()) {
                case READY -> claimChannel();
                case OFF, FAILED -> {
                    say(Component.translatable("chat.dungeontrain.live.no_ffmpeg").withStyle(ChatFormatting.RED));
                    state = State.IDLE;
                    tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
                }
                default -> { /* still downloading */ }
            }
        } else if (state == State.STREAMING && encoder != null && encoder.died()) {
            stop(Component.translatable("chat.dungeontrain.live.encoder_died").withStyle(ChatFormatting.RED));
            tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
        }
    }

    private void claimChannel() {
        Minecraft mc = Minecraft.getInstance();
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        String name = mc.getUser() != null ? mc.getUser().getName() : "";
        if (uuid == null) { state = State.IDLE; return; }
        state = State.CLAIMING;
        int gen = generation;
        LiveFeedClient.claim(uuid, name).thenAcceptAsync(result -> {
            if (gen != generation || state != State.CLAIMING) return;
            Claim c = LiveFeedClient.parseClaim(result);
            if (c == null) {
                say(Component.translatable("chat.dungeontrain.live.unreachable").withStyle(ChatFormatting.RED));
                state = State.IDLE;
                tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
                return;
            }
            beginStreaming(c);
        }, mc);
    }

    private void beginStreaming(Claim c) {
        Minecraft mc = Minecraft.getInstance();
        try {
            sweepStaleDirs();
            Path d = liveRoot().resolve(c.session());
            Files.createDirectories(d);
            int w = LiveFeedClientConfig.captureWidth();
            int h = LiveFeedClientConfig.heightFor(w);
            var ffmpeg = FfmpegSupport.get();
            if (ffmpeg == null) throw new IOException("ffmpeg vanished");
            grabber = new LiveFrameGrabber(w, h);
            encoder = new LiveEncoder(ffmpeg, grabber, d, LiveFeedClientConfig.captureFps(),
                LiveFeedClientConfig.bitrateKbps(), LiveFeedClientConfig.segmentSeconds());
            uploader = new LiveUploader(d, c.token(), by -> mc.execute(() -> cutOff(by)));
            claim = c;
            dir = d;
            streamStartedMs = System.currentTimeMillis();
            state = State.STREAMING;
            LOGGER.info("[DungeonTrain] live stream started: session {} {}x{} @ {} fps, playlist {}", c.session(), w, h,
                LiveFeedClientConfig.captureFps(), c.playlistUrl());
            say(Component.translatable("chat.dungeontrain.live.started").withStyle(ChatFormatting.GREEN));
            if (c.prevName() != null) say(Component.translatable("chat.dungeontrain.live.took_over", c.prevName()).withStyle(ChatFormatting.GRAY));
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] live stream could not start", e);
            say(Component.translatable("chat.dungeontrain.live.encoder_died").withStyle(ChatFormatting.RED));
            teardown(false);
            state = State.IDLE;
            tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
        }
    }

    private void cutOff(String by) {
        if (state != State.STREAMING) return;
        stop(Component.translatable(by == null || by.isBlank() ? "chat.dungeontrain.live.cut_off" : "chat.dungeontrain.live.cut_off_by", by).withStyle(ChatFormatting.YELLOW), false);
        tellServerEnded(LiveStreamEndedPacket.Reason.CUT_OFF);
    }

    /** The server owns the camcorder on our head; it needs to know when the stream ended without its say-so. */
    private static void tellServerEnded(LiveStreamEndedPacket.Reason reason) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) PacketDistributor.sendToServer(new LiveStreamEndedPacket(reason));
    }

    /** Clean stop: flush the ENDLIST playlist, tell the relay, say why. */
    public void stop(@Nullable Component why) {
        stop(why, true);
    }

    private void stop(@Nullable Component why, boolean clean) {
        if (state == State.IDLE) return;
        generation++;
        LOGGER.info("[DungeonTrain] live stream stopping ({}; {} segment(s) uploaded)", clean ? "clean" : "cut off",
            uploader == null ? 0 : uploader.segmentsUploaded());
        state = State.STOPPING;
        teardown(clean);
        state = State.IDLE;
        if (why != null) say(why);
    }

    private void teardown(boolean clean) {
        if (encoder != null) { encoder.close(); encoder = null; }
        if (uploader != null) { uploader.close(clean); uploader = null; }
        if (grabber != null) { grabber.close(); grabber = null; }
        if (clean && claim != null) LiveFeedClient.stop(claim.token());
        claim = null;
        if (dir != null) { deleteTree(dir); dir = null; }
    }

    // ---- capture ------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderFrameEnd(RenderFrameEvent.Post event) {
        INSTANCE.grabFrame();
    }

    private void grabFrame() {
        if (state != State.STREAMING || grabber == null) return;
        long now = System.nanoTime();
        long period = 1_000_000_000L / Math.max(1, LiveFeedClientConfig.captureFps());
        if (now - lastGrabNs < period * 9 / 10) return;
        lastGrabNs = now;
        Minecraft mc = Minecraft.getInstance();
        grabber.grab(mc.getMainRenderTarget());
    }

    // ---- stop triggers ------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.stop(null);
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        INSTANCE.stop(null);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }

    static Path liveRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dungeontrain").resolve("live");
    }

    private static void sweepStaleDirs() {
        Path root = liveRoot();
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> kids = Files.list(root)) {
            kids.forEach(LiveStreamController::deleteTree);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(q -> {
                try { Files.deleteIfExists(q); } catch (IOException ignored) { /* best effort */ }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
