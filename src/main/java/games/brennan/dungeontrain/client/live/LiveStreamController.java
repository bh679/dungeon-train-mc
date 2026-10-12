package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.LiveStreamEndedPacket;
import games.brennan.dungeontrain.net.LiveStreamPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Claim;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Session dirs a {@link LiveTeardown} is still flushing; the next stream's sweep leaves them be. */
    private static final Set<Path> TEARING_DOWN = ConcurrentHashMap.newKeySet();

    public enum State { IDLE, WAIT_FFMPEG, CLAIMING, STREAMING, STOPPING }

    private volatile State state = State.IDLE;
    private int generation; // bumps on every start/stop so a late async reply cannot act on a newer stream
    @Nullable private LiveFrameGrabber grabber;
    @Nullable private LiveEncoder encoder;
    @Nullable private LiveUploader uploader;
    @Nullable private Claim claim;
    @Nullable private Path dir;
    private long lastGrabNs;
    private boolean grabbedThisFrame; // render thread: this frame was already read at an earlier point
    private long streamStartedMs;
    private int waitTicks;
    /** How long the going-live line waits for the relay's first viewer count before saying 0. */
    private static final long LIVE_LINE_FALLBACK_MS = 10_000L;
    private final LiveViewerBands bands = new LiveViewerBands();
    private boolean announcedLive; // render thread: this stream's going-live line has been said

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
            // The server's burn line already says why for these two.
            case STOP_REPLACED -> stop(null);
            case STOP_DIED -> stop(Component.translatable("chat.dungeontrain.live.ended_death"));
            case STOP_LEFT -> stop(null);
            case STOP_REMOVED -> stop(null);
            case STOP_FREE_PLAY -> stop(Component.translatable("chat.dungeontrain.live.free_play").withStyle(ChatFormatting.GRAY));
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private void start() {
        LOGGER.info("[DungeonTrain] live stream requested by the server (ffmpeg {})", FfmpegSupport.state());
        if (state != State.IDLE) stop(null);
        // Livestreaming is a line on the first-launch consent card: the master "use the internet?"
        // switch and the Livestreaming switch both have to be on. Either off → the server hands the
        // headpiece back (FAILED is the no-burn exit) and nothing is sent.
        if (!LiveFeedClientConfig.streamingEnabled() || !DiscordPresenceClientConfig.isGranted()) {
            say(Component.translatable("chat.dungeontrain.live.disabled",
                    LiveOptionsCommand.pathLink("chat.dungeontrain.live.disabled.path"))
                .withStyle(ChatFormatting.GRAY));
            tellServerEnded(LiveStreamEndedPacket.Reason.DISABLED);
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
        } else if (state == State.STREAMING) {
            announceViewers(System.currentTimeMillis());
        }
    }

    /**
     * "You are now live on [Twitch] &amp; [DungeonTrain TV]" ({@link LiveWatchLinks}) then "N people
     * watching" once the relay's first viewer count lands (or after
     * {@link #LIVE_LINE_FALLBACK_MS} with none, as 0), then a line each time the count settles in a
     * new band ({@link LiveViewerBands}).
     */
    private void announceViewers(long nowMs) {
        int viewers = viewerCount();
        if (!announcedLive) {
            if (viewers < 0 && nowMs - streamStartedMs < LIVE_LINE_FALLBACK_MS) return;
            int shown = Math.max(0, viewers);
            announcedLive = true;
            bands.start(shown);
            say(LiveWatchLinks.line());
            say(PluralRules.clause(ClientLanguage.selected(), "chat.dungeontrain.live.watching", shown)
                .withStyle(ChatFormatting.GREEN));
            return;
        }
        if (bands.update(viewers, nowMs)) {
            say(PluralRules.clause(ClientLanguage.selected(), "chat.dungeontrain.live.viewers", viewers)
                .withStyle(ChatFormatting.GRAY));
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
            announcedLive = false;
            if (c.prevName() != null) say(Component.translatable("chat.dungeontrain.live.took_over", c.prevName()).withStyle(ChatFormatting.GRAY));
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] live stream could not start", e);
            say(Component.translatable("chat.dungeontrain.live.encoder_died").withStyle(ChatFormatting.RED));
            teardown(false, false);
            state = State.IDLE;
            tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
        }
    }

    private void cutOff(String by) {
        if (state != State.STREAMING) return;
        // Silent here: the server burns the Live Eye on CUT_OFF and its burn line says why.
        stop(null, false);
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
        stop(why, clean, false);
    }

    private void stop(@Nullable Component why, boolean clean, boolean wait) {
        if (state == State.IDLE) return;
        generation++;
        LOGGER.info("[DungeonTrain] live stream stopping ({}; {} segment(s) uploaded)", clean ? "clean" : "cut off",
            uploader == null ? 0 : uploader.segmentsUploaded());
        state = State.STOPPING;
        teardown(clean, wait);
        state = State.IDLE;
        if (why != null) say(why);
    }

    /**
     * Hands this stream's encoder, uploader, relay claim and directory to {@link LiveTeardown} and
     * clears them, so the controller is IDLE at once. The steps block for seconds — ffmpeg's last
     * segment, the final upload pass, the relay stop — and on the render thread that froze the game
     * every time the Live Eye came off, so they run on their own thread. {@code wait} runs them here
     * instead: game shutdown only, where a daemon thread would die before the ENDLIST went out.
     */
    private void teardown(boolean clean, boolean wait) {
        LiveEncoder enc = encoder;
        LiveUploader up = uploader;
        LiveFrameGrabber grab = grabber;
        Claim c = claim;
        Path d = dir;
        encoder = null;
        uploader = null;
        grabber = null;
        claim = null;
        dir = null;

        List<LiveTeardown.Step> steps = new ArrayList<>();
        if (enc != null) steps.add(new LiveTeardown.Step("encoder", enc::close));
        if (up != null) steps.add(new LiveTeardown.Step("final upload", () -> up.close(clean)));
        if (clean && c != null) steps.add(new LiveTeardown.Step("relay stop", () -> LiveFeedClient.stop(c.token())));
        if (d != null) steps.add(new LiveTeardown.Step("delete session dir", () -> deleteTree(d)));
        // GL buffers belong to the render thread; freed only once the encoder has stopped reading them.
        if (grab != null) steps.add(new LiveTeardown.Step("frame grabber",
            () -> { if (wait) grab.close(); else Minecraft.getInstance().execute(grab::close); }));
        if (steps.isEmpty()) return;

        if (d != null) TEARING_DOWN.add(d);
        Runnable done = () -> { if (d != null) TEARING_DOWN.remove(d); };
        if (wait) {
            LiveTeardown.runAll(steps);
            done.run();
        } else {
            LiveTeardown.runAsync(steps, done);
        }
    }

    // ---- capture ------------------------------------------------------------------------------

    /**
     * NORMAL priority on purpose: the frame is grabbed here, so whatever draws on
     * {@link RenderFrameEvent.Post} at LOW/LOWEST ({@link LiveRecOverlay}, {@link LiveHeadViewer})
     * is on the streamer's screen only and never in the broadcast. Pinned by {@code LiveFrameOrderTest}.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRenderFrameEnd(RenderFrameEvent.Post event) {
        boolean handled = INSTANCE.grabbedThisFrame;
        INSTANCE.grabbedThisFrame = false;
        if (handled) return;
        // An earlier point that never fired (no GUI pass this frame, or another mod removed the chat
        // layer) drops the frame: reading the finished frame instead could send what was kept back.
        StreamCapture.Point point = INSTANCE.capturePoint();
        if (point == StreamCapture.Point.FRAME_END || point == StreamCapture.Point.FRAME_END_HIDE_CHAT) {
            INSTANCE.grabFrame();
        }
    }

    /**
     * Stream GUI off: read the frame before any GUI layer draws (see {@link StreamCapture}). Receives
     * cancelled events, so a mod cancelling the GUI pass cannot push the read to the finished frame.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onGuiStart(RenderGuiEvent.Pre event) {
        if (INSTANCE.capturePoint() == StreamCapture.Point.BEFORE_GUI) INSTANCE.grabNow(event.getGuiGraphics());
    }

    /**
     * Stream chat off (always, in Kid mode): read just before chat draws, or skip the chat layer
     * while a menu is open. Receives cancelled events for the same reason as {@link #onGuiStart}.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!VanillaGuiLayers.CHAT.equals(event.getName())) return;
        switch (INSTANCE.capturePoint()) {
            case BEFORE_CHAT -> INSTANCE.grabNow(event.getGuiGraphics());
            case FRAME_END_HIDE_CHAT -> event.setCanceled(true);
            default -> { }
        }
    }

    private void grabNow(GuiGraphics graphics) {
        if (grabbedThisFrame) return;
        graphics.flush(); // the HUD drawn so far is still batched; put it in the framebuffer first
        grabFrame();
        grabbedThisFrame = true;
    }

    private StreamCapture.Point capturePoint() {
        if (state != State.STREAMING) return StreamCapture.Point.SKIP;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        return StreamCapture.point(LiveFeedClientConfig.streamGui(), streamChatShown(),
            screen != null, screen instanceof ChatScreen, mc.options.hideGui);
    }

    /** The Stream chat switch as it applies to this player — always off in Kid mode. */
    public static boolean streamChatShown() {
        return StreamCapture.chatShown(LiveFeedClientConfig.streamChat(), ClientDisplayConfig.getContentMode().isKid());
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
        INSTANCE.stop(null, true, true);
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
            kids.filter(kid -> !TEARING_DOWN.contains(kid)).forEach(LiveStreamController::deleteTree);
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
