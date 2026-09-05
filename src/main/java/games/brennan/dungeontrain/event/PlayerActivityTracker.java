package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.net.ActivityStatePacket;
import games.brennan.dungeontrain.player.PlayerRunState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Is this player actually playing, and are they getting anywhere? Server-side gate for the two
 * time-on-train counters, which should measure play rather than wall-clock presence:
 * {@link games.brennan.dungeontrain.advancement.GlobalPlayerStats#addTrainTicks} (lifetime) and
 * {@link PlayerRunState#addTrainTimeTicks} (this life), both in {@link BoardingProgressEvents}.
 * Train time is the only clock the game keeps — every player-facing "time" figure reads it.
 *
 * <p>Four triggers stop it:</p>
 * <ul>
 *   <li>Mouse has not moved for {@link #LOOK_IDLE_TICKS} (30 s)</li>
 *   <li>No <em>non-look</em> input for {@link #INPUT_IDLE_TICKS} (5 min)</li>
 *   <li>Pause screen open ({@link games.brennan.dungeontrain.net.PlayerPausedPacket})</li>
 *   <li>Fewer than {@link #MIN_CARRIAGES_PER_WINDOW} carriages traversed in the last
 *       {@link #PROGRESS_WINDOW_TICKS} (10 min) — playing, but not getting anywhere</li>
 * </ul>
 *
 * <p>Looking around deliberately does <em>not</em> refresh the input clock — otherwise the 5-minute
 * rule could never fire, since the 30-second mouse rule would always bite first. The two clocks
 * answer different questions: "is anyone at the keyboard?" and "are they doing anything?"</p>
 *
 * <p><b>Why activity is not measured from position.</b> On a moving train the player's world
 * position changes every tick with nobody at the keyboard — on-train entities are world-space and
 * the Sable sub-level carries them. A position delta cannot tell riding from playing, and neither
 * can vanilla's {@link ServerPlayer#getLastActionTime()}, which movement packets keep resetting.
 * What is left is client-driven and train-independent: look direction, the interaction events
 * subscribed below, a handful of sampled input states, and the carriage index — walking a carriage
 * is proof of keyboard input, and it is the one movement signal the train's own motion cannot
 * fake.</p>
 *
 * <p>One blind spot the server cannot cover on its own: with a screen open the camera stops
 * turning, so sampled yaw sits still however much the mouse moves. The client reports that case
 * directly — see {@link games.brennan.dungeontrain.net.ClientInputPacket}.</p>
 *
 * <p>All state is transient and keyed by UUID — dropped on logout, seeded on login and respawn. A
 * player the tracker has never seen counts as active, so a missed login can only over-count, never
 * silently freeze someone's timers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerActivityTracker {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** No mouse movement for this many ticks (30 s) and both counters stop. */
    public static final long LOOK_IDLE_TICKS = 600L;

    /** No non-look input for this many ticks (5 min) and both counters stop. */
    public static final long INPUT_IDLE_TICKS = 6000L;

    /** Carriage-progress window (10 min). */
    public static final long PROGRESS_WINDOW_TICKS = 12000L;

    /** Carriages a player must traverse within {@link #PROGRESS_WINDOW_TICKS} to keep banking train time. */
    public static final int MIN_CARRIAGES_PER_WINDOW = 3;

    /** Sampling cadence. Matches {@link BoardingProgressEvents}' boarding scan. */
    private static final int SAMPLE_PERIOD_TICKS = 10;

    /**
     * Minimum look change (degrees, either axis) that counts as mouse movement. Above mouse jitter,
     * far below a deliberate glance.
     */
    private static final float LOOK_EPSILON_DEG = 0.5f;

    /** Why a player's clock is (or is not) running. Precedence order — first match wins. */
    public enum Reason {
        /** Both clocks running. */
        TRACKING,
        /** Client reported its pause screen open. */
        PAUSED,
        /** No mouse movement for {@link #LOOK_IDLE_TICKS}. */
        MOUSE_IDLE,
        /** No non-look input for {@link #INPUT_IDLE_TICKS}. */
        INPUT_IDLE,
        /** Aboard, but not getting anywhere — train time only. */
        NO_PROGRESS
    }

    /** Sampled input state; a change in any field is non-look input. */
    private record InputState(int hotbarSlot, boolean sneaking, boolean sprinting, boolean swimming,
                              boolean swinging) {
        static InputState of(ServerPlayer player) {
            return new InputState(player.getInventory().selected, player.isShiftKeyDown(),
                player.isSprinting(), player.isSwimming(), player.swinging);
        }
    }

    /** Player → server game time of their last mouse movement. */
    private static final Map<UUID, Long> LAST_LOOK_TICK = new HashMap<>();

    /** Player → server game time of their last non-look input. */
    private static final Map<UUID, Long> LAST_INPUT_TICK = new HashMap<>();

    /** Player → last sampled {@code [yRot, xRot]}, the baseline the next sample is compared against. */
    private static final Map<UUID, float[]> LAST_LOOK = new HashMap<>();

    /** Player → last sampled input state, the baseline for change detection. */
    private static final Map<UUID, InputState> LAST_INPUT_STATE = new HashMap<>();

    /** Players whose client currently reports a pause screen open. */
    private static final Set<UUID> PAUSED = new HashSet<>();

    /** Player → {@code [tick, pIdx]} samples inside the progress window, oldest first. */
    private static final Map<UUID, Deque<long[]>> CARRIAGE_HISTORY = new HashMap<>();

    /** Player → the tick they first boarded. The progress rule stays silent for one window after it. */
    private static final Map<UUID, Long> FIRST_BOARDED_TICK = new HashMap<>();

    /** What last counted as input for a player — named in the resume log line. */
    private static final Map<UUID, String> LAST_TRIGGER = new HashMap<>();

    /** Last reason logged per player. Only transitions are logged, not every scan. */
    private static final Map<UUID, Reason> REPORTED_REASON = new HashMap<>();

    /** Last state pushed to each player's HUD, so the packet only goes out when something changes. */
    private static final Map<UUID, ActivityStatePacket> LAST_SENT = new HashMap<>();

    private PlayerActivityTracker() {}

    // ---------------------------------------------------------------- queries

    /** Which reasons leave time on the train running — only one. */
    public static boolean countsTrain(Reason reason) {
        return reason == Reason.TRACKING;
    }

    /**
     * Should this player's time on the train advance? Not while paused, not with the mouse still
     * for 30 s or no non-look input for 5 minutes, and not without forward progress through the
     * train.
     */
    public static boolean isCountingTrain(ServerPlayer player) {
        return countsTrain(reason(player));
    }

    /** Why this player's clock is or is not running, in precedence order. */
    public static Reason reason(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (PAUSED.contains(uuid)) return Reason.PAUSED;
        long now = player.level().getGameTime();
        Long lastLook = LAST_LOOK_TICK.get(uuid);
        // Never seen (login handler missed, or a fake player): count rather than freeze silently.
        if (lastLook != null && isIdle(lastLook, now, LOOK_IDLE_TICKS)) return Reason.MOUSE_IDLE;
        Long lastInput = LAST_INPUT_TICK.get(uuid);
        if (lastInput != null && isIdle(lastInput, now, INPUT_IDLE_TICKS)) return Reason.INPUT_IDLE;
        if (!hasCarriageProgress(uuid, now)) return Reason.NO_PROGRESS;
        return Reason.TRACKING;
    }

    /**
     * Has this player traversed {@link #MIN_CARRIAGES_PER_WINDOW} carriages within the window?
     * True while the rule cannot yet judge: before they have ever boarded, and for one full window
     * after they first did — otherwise every fresh spawn would start frozen.
     */
    private static boolean hasCarriageProgress(UUID uuid, long now) {
        Long firstBoarded = FIRST_BOARDED_TICK.get(uuid);
        if (firstBoarded == null) return true;
        if (now - firstBoarded < PROGRESS_WINDOW_TICKS) return true;
        Deque<long[]> history = CARRIAGE_HISTORY.get(uuid);
        prune(history, now);
        // No sample in the last window means they are not aboard a carriage at all (a portal room,
        // say) — which is not progress either.
        if (history == null || history.isEmpty()) return false;
        return carriageSpan(history) >= MIN_CARRIAGES_PER_WINDOW;
    }

    // ------------------------------------------------------------ pure rules

    /** Has {@code lastTick} fallen {@code thresholdTicks} or more behind {@code nowTick}? */
    public static boolean isIdle(long lastTick, long nowTick, long thresholdTicks) {
        return nowTick - lastTick >= thresholdTicks;
    }

    /**
     * Did the look direction move by more than {@code epsilonDeg} on either axis? Yaw is compared
     * on the shortest arc so the 359° → 1° wrap reads as 2°, not 358°.
     */
    public static boolean lookChanged(float prevYaw, float prevPitch,
                                      float yaw, float pitch, float epsilonDeg) {
        float dYaw = Math.abs(Mth.wrapDegrees(yaw - prevYaw));
        float dPitch = Math.abs(pitch - prevPitch);
        return dYaw > epsilonDeg || dPitch > epsilonDeg;
    }

    /**
     * How many carriages the samples span — {@code max − min}. Walking three carriages forward and
     * back again is still three carriages traversed, so the span is the honest measure of "moved
     * through", not the net displacement.
     */
    public static int carriageSpan(Deque<long[]> history) {
        if (history == null || history.isEmpty()) return 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long[] sample : history) {
            min = Math.min(min, sample[1]);
            max = Math.max(max, sample[1]);
        }
        return (int) (max - min);
    }

    /** Drop samples that have aged out of the progress window. */
    private static void prune(Deque<long[]> history, long now) {
        if (history == null) return;
        while (!history.isEmpty() && now - history.peekFirst()[0] > PROGRESS_WINDOW_TICKS) {
            history.removeFirst();
        }
    }

    // ------------------------------------------------------------- recording

    /**
     * Stamp {@code uuid} as having moved the mouse. Called by the sampler below, and by
     * {@link games.brennan.dungeontrain.net.ClientInputPacket} for cursor movement inside an open
     * screen — with a screen up the camera does not turn, so the sampler sees a perfectly still
     * yaw no matter how busy the player is.
     */
    public static void markLook(UUID uuid, long nowTick) {
        LAST_LOOK_TICK.put(uuid, nowTick);
    }

    /** Stamp {@code uuid} as having provided non-look input, crediting {@code trigger}. */
    public static void markInput(UUID uuid, long nowTick, String trigger) {
        LAST_INPUT_TICK.put(uuid, nowTick);
        LAST_TRIGGER.put(uuid, trigger);
    }

    /**
     * Note which carriage a player is standing in, from {@link BoardingProgressEvents}' scan. A
     * change of carriage is also non-look input — the train cannot walk them into the next carriage.
     */
    public static void recordCarriage(UUID uuid, int pIdx, long nowTick) {
        FIRST_BOARDED_TICK.putIfAbsent(uuid, nowTick);
        Deque<long[]> history = CARRIAGE_HISTORY.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        long[] previous = history.peekLast();
        if (previous != null && previous[1] != pIdx) {
            markInput(uuid, nowTick, "walked to carriage " + pIdx);
        }
        history.addLast(new long[] { nowTick, pIdx });
        prune(history, nowTick);
    }

    /**
     * Record the client's pause state. Un-pausing counts as input — the player is back at the
     * keyboard whether or not they move next tick.
     */
    public static void setPaused(ServerPlayer player, boolean paused) {
        UUID uuid = player.getUUID();
        if (paused) {
            PAUSED.add(uuid);
        } else {
            PAUSED.remove(uuid);
            long now = player.level().getGameTime();
            markLook(uuid, now);
            markInput(uuid, now, "un-paused");
        }
    }

    // ----------------------------------------------------------------- scans

    /**
     * Per-scan sampling: look direction (the 30-second clock), input state (the 5-minute clock),
     * then the transition log and the HUD push.
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
            float[] previousLook = LAST_LOOK.get(uuid);
            if (previousLook == null
                    || lookChanged(previousLook[0], previousLook[1], yaw, pitch, LOOK_EPSILON_DEG)) {
                markLook(uuid, now);
            }
            LAST_LOOK.put(uuid, new float[] { yaw, pitch });

            InputState input = InputState.of(player);
            InputState previousInput = LAST_INPUT_STATE.put(uuid, input);
            if (previousInput != null && !previousInput.equals(input)) {
                markInput(uuid, now, "movement / held keys");
            }

            reportTransition(player, now);
            pushState(player, now);
        }
    }

    /**
     * Log the moment a player's clock stops and the moment it starts again — nothing in between.
     * On a dedicated server this is the only visibility; the dev HUD covers the client case.
     */
    private static void reportTransition(ServerPlayer player, long now) {
        UUID uuid = player.getUUID();
        Reason reason = reason(player);
        Reason previous = REPORTED_REASON.put(uuid, reason);
        if (previous == reason) return;
        // First observation of a player is a baseline, not a transition — nothing "resumed".
        if (previous == null) return;
        if (reason == Reason.TRACKING) {
            LOGGER.info("[DungeonTrain] Activity: {} RESUMED counting at tick {} (was {}, trigger: {})",
                player.getName().getString(), now, previous,
                LAST_TRIGGER.getOrDefault(uuid, "look"));
        } else {
            LOGGER.info("[DungeonTrain] Activity: {} STOPPED counting at tick {} — {}{}",
                player.getName().getString(), now, reason,
                reason == Reason.NO_PROGRESS
                    ? " (" + carriageSpan(CARRIAGE_HISTORY.get(uuid)) + "/"
                        + MIN_CARRIAGES_PER_WINDOW + " carriages)"
                    : "");
        }
    }

    /** Push the dev-HUD read-out, but only when something on it would look different. */
    private static void pushState(ServerPlayer player, long now) {
        UUID uuid = player.getUUID();
        Reason reason = reason(player);
        long stoppedSeconds = switch (reason) {
            case MOUSE_IDLE -> (now - LAST_LOOK_TICK.getOrDefault(uuid, now)) / 20L;
            case INPUT_IDLE -> (now - LAST_INPUT_TICK.getOrDefault(uuid, now)) / 20L;
            default -> 0L;
        };
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        ActivityStatePacket packet = new ActivityStatePacket(
            reason == Reason.TRACKING,
            reason.ordinal(),
            (int) Math.min(Integer.MAX_VALUE, stoppedSeconds),
            carriageSpan(CARRIAGE_HISTORY.get(uuid)),
            run.trainTimeTicks());
        if (packet.equals(LAST_SENT.get(uuid))) return;
        LAST_SENT.put(uuid, packet);
        PacketDistributor.sendToPlayer(player, packet);
    }

    // ---------------------------------------------------------- input events

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        mark(event.getEntity(), "use item");
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        mark(event.getEntity(), "use block");
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        mark(event.getEntity(), "hit block");
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        mark(event.getEntity(), "attack");
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        mark(event.getEntity(), "container");
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        mark(event.getPlayer(), "chat");
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        seed(event.getEntity(), "login");
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        seed(event.getEntity(), "respawn");
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        LAST_LOOK_TICK.remove(uuid);
        LAST_INPUT_TICK.remove(uuid);
        LAST_LOOK.remove(uuid);
        LAST_INPUT_STATE.remove(uuid);
        CARRIAGE_HISTORY.remove(uuid);
        FIRST_BOARDED_TICK.remove(uuid);
        LAST_TRIGGER.remove(uuid);
        REPORTED_REASON.remove(uuid);
        LAST_SENT.remove(uuid);
        PAUSED.remove(uuid);
    }

    /** Stamp a server player as having provided non-look input. No-op off the server. */
    private static void mark(net.minecraft.world.entity.player.Player player, String trigger) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        markInput(serverPlayer.getUUID(), serverPlayer.level().getGameTime(), trigger);
    }

    /** Start both clocks fresh — a player who just arrived is at the keyboard. */
    private static void seed(net.minecraft.world.entity.player.Player player, String trigger) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        UUID uuid = serverPlayer.getUUID();
        long now = serverPlayer.level().getGameTime();
        markLook(uuid, now);
        markInput(uuid, now, trigger);
    }
}
