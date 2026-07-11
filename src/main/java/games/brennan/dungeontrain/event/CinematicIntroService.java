package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.CinematicIntroPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side glue for the spawn intro cinematic. Keeps the cinematic
 * concerns (trigger gate, packet dispatch, temporary invulnerability) out of
 * {@link PlayerJoinEvents}.
 *
 * <p>The camera itself is entirely client-side (see {@code CinematicCameraController});
 * this service only decides <em>whether</em> to play, sends the start packet
 * carrying the old ground spawn pose as the camera start, and shields the
 * player from damage while they have no control.</p>
 */
public final class CinematicIntroService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Standing eye height — the cinematic camera starts where the player's eyes were on the ground. */
    private static final double EYE_HEIGHT = 1.62;

    /** Blocks the camera climbs over the cinematic. */
    private static final double RISE_HEIGHT = 22.0;
    /** Blocks the camera eases back (away from the player) over the cinematic. */
    private static final double PULL_BACK = 14.0;
    /** Look target is the player's feet + this, so the camera aims at the upper body, not the deck. */
    private static final double LOOK_Y_OFFSET = 1.2;

    /** Extra ticks of invulnerability beyond the cinematic length — safety net for a dropped done-packet. */
    private static final int INVULN_GRACE_TICKS = 40;

    /**
     * Client-side chunk-preload budget (ticks) for the join intro. When the
     * cinematic is triggered on spawn the client may hold it behind a loading
     * screen for up to this long while the terrain around the shot streams in
     * (see {@code CinematicPreloadGate}). Sent to the client in the packet and
     * folded into the invulnerability deadline so the player stays protected
     * across the wait as well as the cinematic itself. The on-demand replay
     * passes {@code preloadChunks=false} and skips the wait entirely.
     */
    public static final int PRELOAD_MAX_WAIT_TICKS = 200;

    /** Player UUID → server-tick deadline at which spawn-invulnerability is force-cleared. */
    private static final Map<UUID, Long> INVULN_UNTIL = new ConcurrentHashMap<>();

    /**
     * Players whose intro cinematic is currently running. Used to defer the
     * starting-book lightning strike ({@link StartingBookEvents}) until the
     * cinematic ends. Added in {@link #play}; removed on {@link #onClientDone}
     * (precise end) or by the {@link #tick} fallback (missing done-packet).
     */
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();

    private CinematicIntroService() {}

    /** True while {@code player}'s intro cinematic is playing (no control yet). */
    public static boolean isCinematicActive(UUID playerId) {
        return ACTIVE.contains(playerId);
    }

    /**
     * Trigger gate. Single switch point for the "frequency" decision — today:
     * config-enabled AND this player hasn't seen the intro in this world yet.
     */
    public static boolean shouldPlay(ServerPlayer player) {
        if (!DungeonTrainConfig.isIntroCinematicEnabled()) return false;
        return !player.getData(ModDataAttachments.SEEN_INTRO_CINEMATIC.get());
    }

    /**
     * Send the start packet (ground pose → camera start), mark the player as
     * having seen the intro, and open the invulnerability window. Call only
     * after the player's body has been teleported onto the train.
     *
     * @param preloadChunks when {@code true} (the join intro), the client may
     *     hold the cinematic behind a loading screen for up to
     *     {@link #PRELOAD_MAX_WAIT_TICKS} while the terrain around the shot
     *     streams in, and the invulnerability window is widened to match. The
     *     on-demand replay passes {@code false} — chunks are already loaded, so
     *     the cinematic starts immediately.
     */
    public static void play(ServerPlayer player, PlayerJoinEvents.SpawnPlacement groundPose, boolean preloadChunks) {
        int duration = DungeonTrainConfig.getIntroCinematicDurationTicks();
        int preloadMaxWaitTicks =
            (preloadChunks && DungeonTrainConfig.isIntroCinematicChunkPreloadEnabled())
                ? PRELOAD_MAX_WAIT_TICKS : 0;
        CinematicIntroPacket pkt = new CinematicIntroPacket(
            groundPose.x(), groundPose.y() + EYE_HEIGHT, groundPose.z(),
            groundPose.yaw(), groundPose.pitch(),
            RISE_HEIGHT, PULL_BACK, LOOK_Y_OFFSET,
            duration, preloadMaxWaitTicks);
        DungeonTrainNet.sendTo(player, pkt);
        player.setData(ModDataAttachments.SEEN_INTRO_CINEMATIC.get(), Boolean.TRUE);
        ACTIVE.add(player.getUUID());
        // Cover the client-side preload wait as well as the cinematic itself —
        // the player is on a loading screen and can't react during the wait.
        beginInvuln(player, duration + preloadMaxWaitTicks);
        LOGGER.info("[DungeonTrain] Intro cinematic for {}: camStart=({}, {}, {}) yaw={} pitch={} duration={}t preloadWait={}t",
            player.getName().getString(),
            String.format("%.1f", groundPose.x()), String.format("%.1f", groundPose.y() + EYE_HEIGHT),
            String.format("%.1f", groundPose.z()),
            String.format("%.1f", groundPose.yaw()), String.format("%.1f", groundPose.pitch()), duration,
            preloadMaxWaitTicks);
    }

    /** Camera-start source for an on-demand cinematic replay. */
    public enum StartMode {
        /** A random nearby ground spot facing the live train — the join-intro framing. */
        SPAWN,
        /** The player's current eye view. */
        CURRENT
    }

    /**
     * Replay the intro cinematic on demand (the {@code /dungeontrain cinematic} command),
     * regardless of the once-only {@code SEEN_INTRO_CINEMATIC} gate. {@link StartMode#SPAWN}
     * anchors the camera at a random nearby ground spot facing the live train (like the join
     * intro); {@link StartMode#CURRENT} anchors it at the player's current eye pose. Either
     * way the camera then rises and eases back while tracking the player. Never moves the
     * body — {@link #play} only sends the camera packet and opens the invulnerability window
     * (the spawn flow does its own teleport <em>before</em> calling {@code play}).
     *
     * @return the {@link StartMode} actually used — {@code SPAWN} degrades to {@code CURRENT}
     *         when no live train/geometry is available (e.g. outside the train dimension).
     */
    public static StartMode replay(ServerPlayer player, StartMode requested) {
        PlayerJoinEvents.SpawnPlacement pose =
            (requested == StartMode.SPAWN) ? PlayerJoinEvents.computeReplaySpawnPose(player) : null;
        StartMode used = (pose != null) ? StartMode.SPAWN : StartMode.CURRENT;
        if (pose == null) {
            // CURRENT (or SPAWN fallback): start at the player's current eye pose. play()
            // adds EYE_HEIGHT to pose.y(), so the feet block-Y yields an eye-level start.
            pose = new PlayerJoinEvents.SpawnPlacement(
                player.getX(), player.getBlockY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.blockPosition());
        }
        // Replay is triggered in-world with chunks already loaded — no preload wait.
        play(player, pose, false);
        return used;
    }

    private static void beginInvuln(ServerPlayer player, int duration) {
        long deadline = player.serverLevel().getGameTime() + duration + INVULN_GRACE_TICKS;
        INVULN_UNTIL.put(player.getUUID(), deadline);
        player.setInvulnerable(true);
    }

    /** Client signalled the cinematic ended — clear the shield early and release the lightning. */
    public static void onClientDone(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        if (INVULN_UNTIL.remove(player.getUUID()) != null) {
            player.setInvulnerable(false);
        }
    }

    /**
     * Per-tick safety net (hook from an overworld {@code LevelTickEvent.Post}).
     * Clears invulnerability for any player whose window has elapsed even if
     * the client never sent a done-packet.
     */
    public static void tick(MinecraftServer server) {
        if (INVULN_UNTIL.isEmpty()) return;
        long now = server.overworld().getGameTime();
        INVULN_UNTIL.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) p.setInvulnerable(false);
            ACTIVE.remove(e.getKey());
            return true;
        });
    }

    /** Drop tracking on logout (do not touch the now-offline entity). */
    public static void forget(UUID playerId) {
        INVULN_UNTIL.remove(playerId);
        ACTIVE.remove(playerId);
    }
}
