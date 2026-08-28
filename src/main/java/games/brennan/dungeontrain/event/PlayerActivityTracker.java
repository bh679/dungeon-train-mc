package games.brennan.dungeontrain.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import games.brennan.dungeontrain.DungeonTrain;

/**
 * Is this player actually playing? Server-side idle / paused tracking, consulted by the
 * time counters that should measure play rather than wall-clock presence:
 * {@link games.brennan.dungeontrain.advancement.GlobalPlayerStats#addTrainTicks} and
 * {@link games.brennan.dungeontrain.player.PlayerRunState#addTrainTimeTicks} (both in
 * {@link BoardingProgressEvents}) and {@link games.brennan.dungeontrain.player.PlayerRunState#addRunTicks}
 * (in {@link RunStatsEvents}).
 *
 * <p>Two ways to stop counting:</p>
 * <ul>
 *   <li><b>Idle</b> — no activity for {@link #IDLE_TICKS} (5 minutes). Resumes on the next
 *       activity; the five minutes before the threshold is crossed still counts (no claw-back).</li>
 *   <li><b>Paused</b> — the client reported its pause screen open
 *       ({@link games.brennan.dungeontrain.net.PlayerPausedPacket}). Immediate, and the only way
 *       to catch a pause on a dedicated server, where the world keeps ticking behind the menu.
 *       In singleplayer the integrated server stops ticking anyway, so this is belt-and-braces.</li>
 * </ul>
 *
 * <p><b>Why activity is not measured from position.</b> On a moving train the player's world
 * position changes every tick with nobody at the keyboard — on-train entities are world-space and
 * the Sable sub-level carries them. A position delta cannot tell riding from playing, and neither
 * can vanilla's {@link ServerPlayer#getLastActionTime()}, which movement packets keep resetting.
 * What is left is client-driven and train-independent: <b>look direction</b> (sampled here) plus
 * the explicit gameplay actions subscribed below.</p>
 *
 * <p>All state is transient and keyed by UUID — dropped on logout, seeded on login and respawn.
 * A player the tracker has never seen counts as active, so a missed login can only over-count,
 * never silently freeze someone's timers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerActivityTracker {

    /** No activity for this many server ticks (5 min) and the time counters stop. */
    public static final long IDLE_TICKS = 6000L;

    /** Look-sampling cadence. Matches {@link BoardingProgressEvents}' boarding scan. */
    private static final int SAMPLE_PERIOD_TICKS = 10;

    /**
     * Minimum look change (degrees, either axis) that counts as activity. Above mouse jitter,
     * far below a deliberate glance.
     */
    private static final float LOOK_EPSILON_DEG = 0.5f;

    /** Player → server game time of their last activity. */
    private static final Map<UUID, Long> LAST_ACTIVE_TICK = new HashMap<>();

    /** Player → last sampled {@code [yRot, xRot]}, the baseline the next sample is compared against. */
    private static final Map<UUID, float[]> LAST_LOOK = new HashMap<>();

    /** Players whose client currently reports a pause screen open. */
    private static final Set<UUID> PAUSED = new HashSet<>();

    private PlayerActivityTracker() {}

    /**
     * Should this player's time counters advance? False while their client reports paused, or
     * once they have been idle for {@link #IDLE_TICKS}.
     */
    public static boolean isCounting(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (PAUSED.contains(uuid)) return false;
        Long last = LAST_ACTIVE_TICK.get(uuid);
        // Never seen (login handler missed, or a fake player): count rather than freeze silently.
        if (last == null) return true;
        return !isIdle(last, player.level().getGameTime(), IDLE_TICKS);
    }

    /** Stamp {@code uuid} as active as of {@code nowTick}. */
    public static void markActive(UUID uuid, long nowTick) {
        LAST_ACTIVE_TICK.put(uuid, nowTick);
    }

    /**
     * Record the client's pause state. Un-pausing also counts as activity — the player is back
     * at the keyboard whether or not they move next tick.
     */
    public static void setPaused(ServerPlayer player, boolean paused) {
        UUID uuid = player.getUUID();
        if (paused) {
            PAUSED.add(uuid);
        } else {
            PAUSED.remove(uuid);
            markActive(uuid, player.level().getGameTime());
        }
    }

    /** Has {@code lastActiveTick} fallen {@code thresholdTicks} or more behind {@code nowTick}? */
    public static boolean isIdle(long lastActiveTick, long nowTick, long thresholdTicks) {
        return nowTick - lastActiveTick >= thresholdTicks;
    }

    /**
     * Did the look direction move by more than {@code epsilonDeg} on either axis? Yaw is compared
     * on the shortest arc so the 359° → 1° wrap reads as 2°, not 358°.
     */
    public static boolean lookChanged(float prevYaw, float prevPitch,
                                      float yaw, float pitch, float epsilonDeg) {
        float dYaw = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - prevYaw));
        float dPitch = Math.abs(pitch - prevPitch);
        return dYaw > epsilonDeg || dPitch > epsilonDeg;
    }

    /**
     * Look sampling. Every {@link #SAMPLE_PERIOD_TICKS} ticks each online player's yaw/pitch is
     * compared against the last sample; a change beyond {@link #LOOK_EPSILON_DEG} is activity.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % SAMPLE_PERIOD_TICKS != 0L) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            float yaw = player.getYRot();
            float pitch = player.getXRot();
            float[] prev = LAST_LOOK.get(uuid);
            if (prev == null || lookChanged(prev[0], prev[1], yaw, pitch, LOOK_EPSILON_DEG)) {
                markActive(uuid, now);
            }
            LAST_LOOK.put(uuid, new float[] { yaw, pitch });
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        mark(event.getPlayer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        mark(event.getEntity());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        LAST_ACTIVE_TICK.remove(uuid);
        LAST_LOOK.remove(uuid);
        PAUSED.remove(uuid);
    }

    /** Stamp a server player as active now. No-op for client-side or non-server players. */
    private static void mark(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        markActive(serverPlayer.getUUID(), serverPlayer.level().getGameTime());
    }
}
