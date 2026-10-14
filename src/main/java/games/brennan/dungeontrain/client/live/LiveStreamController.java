package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.cheat.ApprovedModList;
import games.brennan.dungeontrain.client.live.audio.AudioMirror;
import games.brennan.dungeontrain.client.live.audio.LiveAudioPump;
import games.brennan.dungeontrain.client.live.audio.StreamSoundPolicy;
import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.relay.LatestReleaseCache;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.LiveStreamEndedPacket;
import games.brennan.dungeontrain.net.LiveStreamPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Claim;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    /** Game shutdown's whole budget for ending a stream, so a bad link cannot hang the quit. */
    static final long SHUTDOWN_BUDGET_MS = 8_000;
    /** Of that budget, what the final upload leaves for the relay stop. */
    private static final long RELAY_STOP_RESERVE_MS = 1_500;
    private static final long RELAY_STOP_MIN_MS = 500;
    /** The relay stop's wait on a teardown off the render thread. */
    private static final long RELAY_STOP_TIMEOUT_MS = 10_000;
    /** {@code deadlineMs} of a teardown that runs on its own thread, with each step's own timeout. */
    private static final long ASYNC = 0;
    /** Spawns and restarts the encoder off the render thread, one at a time so two never share a grabber. */
    private static final ExecutorService STARTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dt-live-start");
        t.setDaemon(true);
        return t;
    });

    public enum State { IDLE, WAIT_FFMPEG, CLAIMING, STREAMING, STOPPING }

    private volatile State state = State.IDLE;
    private int generation; // bumps on every start/stop so a late async reply cannot act on a newer stream
    @Nullable private LiveFrameGrabber grabber;
    @Nullable private LiveEncoder encoder;
    /** The stream's sound, when Options → Stream → Stream sound is on and this client can do it. */
    @Nullable private LiveAudioPump audio;
    @Nullable private LiveUploader uploader;
    @Nullable private Claim claim;
    /** The worn Live Eye's id from the server's START; the relay matches reconnects by it. */
    private String eyeId = "";
    @Nullable private Path dir;
    private long lastGrabNs;
    private boolean grabbedThisFrame; // render thread: this frame was already read at an earlier point
    private long streamStartedMs;
    private int waitTicks;
    /** The Live Eye must be on this client's head: the server's START alone is not enough. */
    private final LiveHeadpieceGuard headpiece = new LiveHeadpieceGuard();
    /** How long the going-live line waits for the relay's first viewer count before saying 0. */
    private static final long LIVE_LINE_FALLBACK_MS = 10_000L;
    private final LiveViewerBands bands = new LiveViewerBands();
    private boolean announcedLive; // render thread: this stream's going-live line has been said
    /**
     * Render thread: the "Livestream ended" pop-up of a stream that ended by leaving the world, held
     * until the world is gone — vanilla's {@code Gui.onDisconnected} clears every toast after
     * LoggingOut fires, so one raised there would never be seen.
     */
    @Nullable private LiveStreamEndedToast.Reason endedAfterLeave;

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

    /** Render thread: the running encoder, or null — for the {@code [live]} perf line. */
    @Nullable
    LiveEncoder encoder() {
        return encoder;
    }

    /** Render thread: the running frame grabber, or null — for the {@code [live]} perf line. */
    @Nullable
    LiveFrameGrabber grabber() {
        return grabber;
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
            case START -> start(packet.eyeId());
            // The ended pop-up says why; the server's burn line describes the Live Eye.
            case STOP_REPLACED -> stop(null, LiveStreamEndedToast.Reason.REPLACED);
            case STOP_DIED -> stop(null, LiveStreamEndedToast.Reason.DIED);
            case STOP_LEFT -> stop(null, LiveStreamEndedToast.Reason.LEFT);
            case STOP_REMOVED -> stop(null, LiveStreamEndedToast.Reason.REMOVED);
            case STOP_FREE_PLAY -> stop(Component.translatable("chat.dungeontrain.live.free_play").withStyle(ChatFormatting.GRAY), LiveStreamEndedToast.Reason.FREE_PLAY);
            // The server's burn line and strike warning already said why.
            case STOP_MODERATED -> stop(null, LiveStreamEndedToast.Reason.MODERATED);
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private void start(String eyeId) {
        this.eyeId = eyeId == null ? "" : eyeId;
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
        FfmpegSupport.aacEncoder(); // probe off-thread now, so it has its answer by the time the claim lands
        headpiece.reset();
        switch (FfmpegSupport.state()) {
            case OFF, FAILED -> {
                say(Component.translatable("chat.dungeontrain.live.no_ffmpeg").withStyle(ChatFormatting.RED));
                state = State.IDLE;
                tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
            }
            case DOWNLOADING -> say(Component.translatable("chat.dungeontrain.live.preparing").withStyle(ChatFormatting.GRAY));
            // No claim until the Live Eye is on: claiming cuts off whoever streams in another world.
            // Not on yet (login: the inventory may land after START) → the tick claims once it is.
            case READY -> { if (headpieceWorn()) claimChannel(); }
        }
    }

    /** The Live Eye is on this client's own head, as far as this client can see. */
    private static boolean headpieceWorn() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.LIVE_HEADPIECE.get());
    }

    /**
     * Ends a stream whose Live Eye is not on this client's head ({@link LiveHeadpieceGuard}). The
     * server hears FAILED, the no-burn exit: a real take-off was already burned and cleared by the
     * server, so this only reaches a server that still thinks it is worn, and that hands it back.
     * True when the stream ended.
     */
    private boolean checkHeadpiece() {
        LiveHeadpieceGuard.Verdict v = headpiece.tick(headpieceWorn(), state == State.STREAMING);
        if (v == LiveHeadpieceGuard.Verdict.NONE) return false;
        if (v == LiveHeadpieceGuard.Verdict.REFUSE) {
            LOGGER.warn("[DungeonTrain] live stream refused: headpiece not worn");
            stop(null);
        } else {
            LOGGER.info("[DungeonTrain] live stream ended: headpiece no longer worn");
            stop(null, LiveStreamEndedToast.Reason.REMOVED);
        }
        tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
        return true;
    }

    /** Client tick: finish waiting for ffmpeg, and watch the encoder while streaming. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        INSTANCE.tick();
    }

    private void tick() {
        if (endedAfterLeave != null && Minecraft.getInstance().level == null) {
            Minecraft.getInstance().getToasts().addToast(new LiveStreamEndedToast(endedAfterLeave));
            endedAfterLeave = null;
        }
        if (state != State.IDLE && state != State.STOPPING && checkHeadpiece()) return;
        if (state == State.WAIT_FFMPEG) {
            if (FfmpegSupport.state() == FfmpegSupport.State.READY) {
                if (headpieceWorn()) claimChannel();
                return;
            }
            if (++waitTicks % 20 != 0) return;
            switch (FfmpegSupport.state()) {
                case OFF, FAILED -> {
                    say(Component.translatable("chat.dungeontrain.live.no_ffmpeg").withStyle(ChatFormatting.RED));
                    state = State.IDLE;
                    tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
                }
                default -> { /* still downloading */ }
            }
        } else if (state == State.STREAMING && encoder != null && encoder.died()) {
            if (audio != null && restartWithoutSound()) return;
            stop(Component.translatable("chat.dungeontrain.live.encoder_died").withStyle(ChatFormatting.RED), LiveStreamEndedToast.Reason.FAILED);
            tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
        } else if (state == State.STREAMING) {
            announceViewers(System.currentTimeMillis());
            // The approved list can change under a running stream (the relay's overlay revokes a mod).
            if (audio != null && ++waitTicks % MOD_RECHECK_TICKS == 0) AudioMirror.setSilenced(!soundAllowedByMods(false));
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
        LatestReleaseCache.refreshIfStale();
        LiveClaimInfo info = LiveClaimInfo.of(VersionInfo.VERSION, LatestReleaseCache.latestVersion(),
            VersionInfo.LAST_UPDATE_DATE, LocalDate.now(ZoneOffset.UTC),
            LiveFeedClientConfig.liveChat(), ClientDisplayConfig.isKidMode());
        boolean freePlay = mc.player != null && mc.player.hasEffect(ModMobEffects.FREE_PLAY);
        LiveFeedClient.Life life = new LiveFeedClient.Life(LiveLife.current(), freePlay, VersionInfo.VERSION);
        CompletableFuture<Boolean> aac = LiveFeedClientConfig.streamAudio()
            ? FfmpegSupport.aacEncoder() : CompletableFuture.completedFuture(false);
        LiveFeedClient.claim(uuid, name, eyeId, info, life).thenAcceptBothAsync(aac, (result, hasAac) -> {
            if (gen != generation || state != State.CLAIMING) return;
            if (result.forbidden() && "banned".equals(result.str("error"))) {
                // The text-filter ladder has this player banned from the Live Eye; the server burns it
                // on BANNED and says for how long.
                state = State.IDLE;
                tellServerEnded(LiveStreamEndedPacket.Reason.BANNED);
                return;
            }
            Claim c = LiveFeedClient.parseClaim(result);
            if (c == null) {
                say(Component.translatable("chat.dungeontrain.live.unreachable").withStyle(ChatFormatting.RED));
                state = State.IDLE;
                tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
                return;
            }
            beginStreaming(c, hasAac);
        }, mc);
    }

    /**
     * Render thread, still CLAIMING: everything that must happen here (the GL grabber, the sound
     * mirror) is set up, then ffmpeg is spawned on {@link #STARTER} and {@link #adoptEncoder} flips
     * to STREAMING back on the render thread. The claim is held from here so a stop meanwhile still
     * tells the relay.
     */
    private void beginStreaming(Claim c, boolean aac) {
        Minecraft mc = Minecraft.getInstance();
        int gen = generation;
        try {
            sweepStaleDirs();
            Path d = liveRoot().resolve(c.session());
            Files.createDirectories(d);
            int w = LiveFeedClientConfig.captureWidth();
            int h = LiveFeedClientConfig.heightFor(w);
            var ffmpeg = FfmpegSupport.get();
            if (ffmpeg == null) throw new IOException("ffmpeg vanished");
            StreamNameTag.prepare();
            claim = c;
            dir = d;
            LiveFrameGrabber grab = new LiveFrameGrabber(w, h);
            grabber = grab;
            int fps = LiveFeedClientConfig.captureFps();
            int bitrate = LiveFeedClientConfig.bitrateKbps();
            int segment = LiveFeedClientConfig.segmentSeconds();
            // Off, not possible here, or an unapproved mod loaded (it could play anything):
            // the stream goes out without sound, as before.
            audio = LiveFeedClientConfig.streamAudio() && soundAllowedByMods(true) ? LiveAudioPump.open(aac) : null;
            int audioPort = audio == null ? 0 : audio.port();
            CompletableFuture.supplyAsync(() -> spawnEncoder(ffmpeg, grab, d, fps, bitrate, segment, audioPort), STARTER)
                .whenCompleteAsync((enc, err) -> adoptEncoder(gen, enc, err, fps), mc);
        } catch (Exception e) {
            failStart(e);
        }
    }

    /** Render thread: the encoder spawned for stream {@code gen} is up (or failed) — go live with it. */
    private void adoptEncoder(int gen, @Nullable LiveEncoder enc, @Nullable Throwable err, int fps) {
        if (gen != generation || state != State.CLAIMING) {
            if (enc != null) closeEncoderAsync(enc); // stopped meanwhile; the teardown already ran
            return;
        }
        Claim c = claim;
        Path d = dir;
        if (err != null || enc == null || c == null || d == null) {
            failStart(err != null ? err : new IllegalStateException("stream state lost while starting"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        encoder = enc;
        if (audio != null) audio.start(enc::framesWritten, fps);
        uploader = new LiveUploader(d, c.token(), by -> mc.execute(() -> cutOff(by)));
        streamStartedMs = System.currentTimeMillis();
        state = State.STREAMING;
        LOGGER.info("[DungeonTrain] live stream started: session {} {}x{} @ {} fps{}, playlist {}", c.session(),
            grabber == null ? 0 : grabber.width(), grabber == null ? 0 : grabber.height(),
            fps, audio != null ? " with sound" : "", c.playlistUrl());
        announcedLive = false;
        if (c.prevName() != null) say(Component.translatable("chat.dungeontrain.live.took_over", c.prevName()).withStyle(ChatFormatting.GRAY));
    }

    /** Render thread: the stream could not start — say so, free what was set up, hand the headpiece back. */
    private void failStart(Throwable e) {
        LOGGER.error("[DungeonTrain] live stream could not start", e);
        say(Component.translatable("chat.dungeontrain.live.encoder_died").withStyle(ChatFormatting.RED));
        generation++;
        teardown(false, ASYNC, null);
        state = State.IDLE;
        tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
    }

    /** {@link #STARTER} thread: spawning ffmpeg can take a while, so never on the render thread. */
    private static LiveEncoder spawnEncoder(FFmpeg ffmpeg, LiveFrameGrabber grab, Path d, int fps, int bitrate,
                                            int segment, int audioPort) {
        try {
            return new LiveEncoder(ffmpeg, grab, d, fps, bitrate, segment, audioPort);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    /** An encoder nobody will use: closed on the teardown thread (closing blocks for seconds). */
    private static void closeEncoderAsync(LiveEncoder enc) {
        LiveTeardown.runAsync(List.of(new LiveTeardown.Step("stray encoder", enc::close)), () -> { });
    }

    /**
     * ffmpeg died on a stream with sound before anything went out — most likely it could not open the
     * sound input. Rather than end the broadcast, start the encoder again without sound, as it would
     * have been with the setting off. Only before the first upload: a fresh encoder numbers its
     * segments from 0 again. True when the stream carries on.
     *
     * <p>Closing the old encoder and sound blocks for seconds, so it runs on {@link #STARTER}, as does
     * the new spawn; the tick's died-check skips while {@code encoder} is null in between.</p>
     */
    private boolean restartWithoutSound() {
        LiveUploader up = uploader;
        var ffmpeg = FfmpegSupport.get();
        LiveFrameGrabber grab = grabber;
        Path d = dir;
        LiveEncoder old = encoder;
        LiveAudioPump snd = audio;
        if (up == null || up.segmentsUploaded() > 0 || ffmpeg == null || grab == null || d == null
            || old == null || snd == null) return false;
        LOGGER.warn("[DungeonTrain] live encoder failed with stream sound on; carrying on without sound");
        audio = null;
        encoder = null;
        int gen = generation;
        int fps = LiveFeedClientConfig.captureFps();
        int bitrate = LiveFeedClientConfig.bitrateKbps();
        int segment = LiveFeedClientConfig.segmentSeconds();
        CompletableFuture.supplyAsync(() -> {
            snd.close();
            old.close();
            return spawnEncoder(ffmpeg, grab, d, fps, bitrate, segment, 0);
        }, STARTER).whenCompleteAsync((enc, err) -> {
            if (gen != generation || state != State.STREAMING) {
                if (enc != null) closeEncoderAsync(enc);
                return;
            }
            if (err != null || enc == null) {
                LOGGER.error("[DungeonTrain] live encoder could not restart without sound", err);
                stop(Component.translatable("chat.dungeontrain.live.encoder_died").withStyle(ChatFormatting.RED), LiveStreamEndedToast.Reason.FAILED);
                tellServerEnded(LiveStreamEndedPacket.Reason.FAILED);
                return;
            }
            encoder = enc;
        }, Minecraft.getInstance());
        return true;
    }

    /** How often a stream with sound re-checks the loaded mods against the approved list. */
    private static final int MOD_RECHECK_TICKS = 200;

    /**
     * Stream sound only goes out while every loaded mod is approved: an unapproved one could play
     * whatever it likes through the sound engine. {@code tell} says so in chat when it is not.
     */
    private static boolean soundAllowedByMods(boolean tell) {
        List<String> loaded = ModList.get().getMods().stream().map(IModInfo::getModId).toList();
        List<String> unapproved = StreamSoundPolicy.unapproved(loaded, ApprovedModList::isApproved);
        if (unapproved.isEmpty()) return true;
        if (tell) {
            LOGGER.warn("[DungeonTrain] stream sound off: unapproved mods loaded {}", unapproved);
            say(Component.translatable("chat.dungeontrain.live.sound_off_unapproved").withStyle(ChatFormatting.GRAY));
        }
        return false;
    }

    private void cutOff(String by) {
        if (state != State.STREAMING) return;
        // Silent here: the server burns the Live Eye on CUT_OFF and its burn line says why.
        stop(null, false, ASYNC, LiveStreamEndedToast.Reason.REPLACED);
        tellServerEnded(LiveStreamEndedPacket.Reason.CUT_OFF);
    }

    /** The server owns the camcorder on our head; it needs to know when the stream ended without its say-so. */
    private static void tellServerEnded(LiveStreamEndedPacket.Reason reason) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) PacketDistributor.sendToServer(new LiveStreamEndedPacket(reason));
    }

    /** Clean stop: flush the ENDLIST playlist, tell the relay, say why. */
    public void stop(@Nullable Component why) {
        stop(why, null);
    }

    private void stop(@Nullable Component why, @Nullable LiveStreamEndedToast.Reason ended) {
        stop(why, true, ASYNC, ended);
    }

    /**
     * {@code ended} raises the top-right "Livestream ended" pop-up, but only for a stream that had
     * gone live: a start that never reached STREAMING says why in chat instead. Raised after the
     * state is IDLE, so the broadcast never captures it. {@code deadlineMs} other than {@link #ASYNC}
     * tears down on this thread and is done by then (game shutdown).
     */
    private void stop(@Nullable Component why, boolean clean, long deadlineMs, @Nullable LiveStreamEndedToast.Reason ended) {
        if (state == State.IDLE) return;
        boolean wasLive = state == State.STREAMING;
        generation++;
        LOGGER.info("[DungeonTrain] live stream stopping ({}; {} segment(s) uploaded)", clean ? "clean" : "cut off",
            uploader == null ? 0 : uploader.segmentsUploaded());
        state = State.STOPPING;
        teardown(clean, deadlineMs, ended);
        state = State.IDLE;
        if (why != null) say(why);
        if (!wasLive || ended == null) return;
        if (ended == LiveStreamEndedToast.Reason.LEFT) {
            endedAfterLeave = ended;
        } else {
            Minecraft.getInstance().getToasts().addToast(new LiveStreamEndedToast(ended));
        }
    }

    /**
     * Hands this stream's encoder, uploader, relay claim and directory to {@link LiveTeardown} and
     * clears them, so the controller is IDLE at once. The steps block for seconds — ffmpeg's last
     * segment, the final upload pass, the relay stop — and on the render thread that froze the game
     * every time the Live Eye came off, so they run on their own thread. A {@code deadlineMs} runs them
     * here instead, every wait cut to fit before it: game shutdown only, where a daemon thread would
     * die before the ENDLIST went out and a bad link must not hang the quit.
     */
    private void teardown(boolean clean, long deadlineMs, @Nullable LiveStreamEndedToast.Reason ended) {
        boolean wait = deadlineMs != ASYNC;
        LiveEncoder enc = encoder;
        LiveAudioPump snd = audio;
        LiveUploader up = uploader;
        LiveFrameGrabber grab = grabber;
        Claim c = claim;
        Path d = dir;
        encoder = null;
        audio = null;
        uploader = null;
        grabber = null;
        claim = null;
        dir = null;

        List<LiveTeardown.Step> steps = new ArrayList<>();
        // Sound first: ffmpeg finishes only once every input has ended.
        if (snd != null) steps.add(new LiveTeardown.Step("audio", snd::close));
        if (enc != null) steps.add(new LiveTeardown.Step("encoder", () -> enc.close(wait
            ? Math.min(LiveEncoder.SHUTDOWN_GRACE_MS, left(deadlineMs)) : LiveEncoder.ASYNC_GRACE_MS)));
        if (up != null) steps.add(new LiveTeardown.Step("final upload", () -> up.close(clean, wait
            ? Math.max(0, left(deadlineMs) - RELAY_STOP_RESERVE_MS) : LiveUploader.CLOSE_BUDGET_MS)));
        String reason = ended == null ? null : ended.relayReason;
        if (clean && c != null) steps.add(new LiveTeardown.Step("relay stop", () -> relayStop(c.token(), reason, wait
            ? Math.max(RELAY_STOP_MIN_MS, left(deadlineMs)) : RELAY_STOP_TIMEOUT_MS)));
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

    private static long left(long deadlineMs) {
        return Math.max(0, deadlineMs - System.currentTimeMillis());
    }

    /** Tells the relay the stream is over and waits up to {@code timeoutMs} for it to hear. */
    private static void relayStop(String token, @Nullable String reason, long timeoutMs) {
        try {
            LiveFeedClient.stop(token, reason).orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            LOGGER.warn("[DungeonTrain] live stop: relay did not answer within {} ms", timeoutMs);
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
     * while a listed menu is open. Receives cancelled events for the same reason as {@link #onGuiStart}.
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

    /**
     * An unlisted screen open with chat on: read once the HUD is drawn and before the screen is, so
     * the chat box, a sign or book being written, or any other menu never reaches viewers.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onGuiEnd(RenderGuiEvent.Post event) {
        if (INSTANCE.capturePoint() == StreamCapture.Point.AFTER_GUI) INSTANCE.grabNow(event.getGuiGraphics());
    }

    private void grabNow(GuiGraphics graphics) {
        if (grabbedThisFrame) return;
        graphics.flush(); // the HUD drawn so far is still batched; put it in the framebuffer first
        grabFrame();
        grabbedThisFrame = true;
    }

    private StreamCapture.Point capturePoint() {
        // Not worn → nothing goes out, from the first frame; the guard's grace only delays ending.
        if (state != State.STREAMING || !headpieceWorn()) return StreamCapture.Point.SKIP;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        return StreamCapture.point(LiveFeedClientConfig.streamGui(), streamChatShown(),
            screen != null, StreamableScreens.shown(screen), mc.options.hideGui);
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
        // Save & quit, disconnect or kick: the pop-up carries over to the title screen.
        INSTANCE.stop(null, LiveStreamEndedToast.Reason.LEFT);
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        long start = System.currentTimeMillis();
        long deadline = start + SHUTDOWN_BUDGET_MS;
        boolean live = INSTANCE.state != State.IDLE;
        INSTANCE.stop(null, true, deadline, null);
        // A stream that ended just before (quit right after leaving the world) may still be uploading.
        boolean earlierDone = LiveTeardown.awaitLast(left(deadline));
        if (live || !earlierDone) {
            LOGGER.info("[DungeonTrain] live stop on shutdown took {} ms", System.currentTimeMillis() - start);
        }
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
