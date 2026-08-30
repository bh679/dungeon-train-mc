package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.api.DespawnCondition;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollEndEvent;
import dev.leo.sableplayerragdoll.api.RagdollLaunchOptions;
import dev.leo.sableplayerragdoll.api.RagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollStartEvent;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.DeathRagdollEvents;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridge into Sable: Ragdolls. Hard imports of {@code dev.leo.sableplayerragdoll.*} are confined
 * to this class; callers gate on {@code ModList.isLoaded("sable_player_ragdoll")} +
 * {@code catch (Throwable)} (same pattern as {@link TradeEverythingBridge}), so a ragdoll build
 * that predates part of the API degrades to an ordinary instant death rather than crashing.
 *
 * <p>Two jobs, and the second is the reason "ragdolls" is a <em>death</em> feature in DT rather
 * than a toy: {@link #launch} starts the body {@link DeathRagdollEvents} holds the death open for,
 * and {@link #onRagdollStart} <b>cancels every ragdoll DT did not itself start</b> — the mod's own
 * tumble keybind, its commands, and any addon's launches. DT cannot write another mod's per-world
 * server config, so the mod's own cancellable start event is the only enforcement point DT owns.</p>
 *
 * <p>Correlation across the mod's <em>asynchronous</em> launch (it round-trips to the dying
 * player's client for a pose snapshot before assembling, so {@code RagdollAPI.launch} returns
 * {@code null} in the normal path and {@code RagdollStartEvent} fires a tick or more later) is on
 * the death hold itself, keyed by player UUID: the hold is opened synchronously inside
 * {@code LivingDeathEvent}, before the launch, and outlives the assembly. A start event with no
 * hold behind it is, by definition, not a death.</p>
 */
public final class SableRagdollBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The ragdoll mod's id — DT declares it as a required dependency in {@code neoforge.mods.toml}. */
    public static final String RAGDOLL_MOD_ID = "sable_player_ragdoll";

    private static final LogFirstN LAUNCH_FAILURES = new LogFirstN(5);

    /** Blocked launches are a normal, player-driven event (someone pressed H) — log a few, then stop. */
    private static final int MAX_BLOCKED_LOGS = 3;
    private static final AtomicInteger BLOCKED_LOGGED = new AtomicInteger();

    private SableRagdollBridge() {}

    /**
     * Subscribe to the ragdoll mod's start/end events. Registered programmatically rather than by
     * annotation so this class is never classloaded when the mod is absent.
     */
    public static void install() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SableRagdollBridge::onRagdollStart);
        NeoForge.EVENT_BUS.addListener(SableRagdollBridge::onRagdollEnd);
    }

    /**
     * Launch {@code player}'s death ragdoll. {@code velocity} is world-space m/s — for a death
     * aboard a moving carriage it carries the carriage's own velocity, or the body is left behind
     * within a few ticks.
     *
     * <p>{@code despawnTicks} is not optional: the mod's despawn toggles all default to off, so
     * without a per-launch expiry every death would leave a body in the world forever.</p>
     *
     * @return true if the launch was accepted — NOT that the body exists yet. Assembly is async
     *         and can still fail silently, which is what the caller's assemble deadline is for.
     */
    public static boolean launch(ServerPlayer player, Vec3 velocity, int despawnTicks) {
        try {
            RagdollAPI.launch(player, velocity, RagdollLaunchOptions.builder()
                    .autoSeat(true)      // the camera rides the body — the whole point
                    .lockDismount(true)  // a dying player does not get to stand back up
                    .despawnConditions(List.of(DespawnCondition.afterTicks(despawnTicks)))
                    .build());
            return true;
        } catch (Throwable t) {
            LAUNCH_FAILURES.error(LOGGER, "[DungeonTrain] death ragdoll launch failed; dying normally", t);
            return false;
        }
    }

    /** End {@code player}'s ragdoll, if any. Called just before the held death is completed. */
    public static void end(ServerPlayer player) {
        try {
            RagdollSession session = RagdollAPI.activeSession(player);
            if (session != null) session.release();
        } catch (Throwable t) {
            LAUNCH_FAILURES.error(LOGGER, "[DungeonTrain] could not end a death ragdoll", t);
        }
    }

    /** True while the ragdoll mod has an active body for this player. */
    public static boolean isRagdolled(ServerPlayer player) {
        try {
            return RagdollAPI.isRagdolled(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cancel any ragdoll that is not the one DT started for this player's death. */
    private static void onRagdollStart(RagdollStartEvent event) {
        if (!DungeonTrainConfig.isDeathRagdollBlockOtherTriggers()) return;
        ServerPlayer player = event.player();
        if (player == null) return;
        if (DeathRagdollEvents.isHeld(player.getUUID())) {
            DeathRagdollEvents.markAssembled(player.getUUID());
            return;
        }
        event.setCanceled(true);
        if (BLOCKED_LOGGED.incrementAndGet() <= MAX_BLOCKED_LOGS) {
            LOGGER.info("[DungeonTrain] cancelled a non-death ragdoll for {} — ragdolls are a death-only "
                    + "effect here (set deathRagdollBlockOtherTriggers=false to allow the tumble keybind)",
                    player.getGameProfile().getName());
        }
    }

    /**
     * The body ended early — settled, expired, or the mod tore it down. Finish the death now
     * instead of holding the player at 0 hearts for the rest of the window.
     */
    private static void onRagdollEnd(RagdollEndEvent event) {
        ServerPlayer player = event.player();
        if (player != null) DeathRagdollEvents.onRagdollEnded(player.getUUID());
    }
}
