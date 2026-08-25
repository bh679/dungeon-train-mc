package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cheats being <b>available</b> — not just used — turns the session into <b>Free Play</b>
 * (see {@link RunIntegrity}).
 *
 * <p><b>Why availability and not use.</b> The command path
 * ({@link games.brennan.dungeontrain.event.CheatDetectionEvents#onCommand}) only sees things that
 * reach Brigadier, and several routes to cheated gear never do:
 * <ul>
 *   <li><b>REI / JEI cheat mode.</b> In single player the integrated server shares the JVM, so REI
 *       is present server-side and cheat-mode item creation travels over REI's own packet — no
 *       command is ever dispatched, so nothing taints.</li>
 *   <li><b>Item / NBT editors</b> that write the stack directly in a creative-style GUI.</li>
 *   <li><b>Another player handing you the loot</b> — the LAN host runs {@code /give}, taints their
 *       own run, and you keep clean stats holding their diamonds.</li>
 * </ul>
 * Every one of those needs permission level {@value #OPERATOR_PERMISSION_LEVEL} to work, so that
 * one precondition is the reliable choke point the individual mechanisms are not.
 *
 * <p><b>Session-wide.</b> If <em>anyone</em> online is an operator, the whole session is Free Play
 * for everyone — items move between players, so a per-player scope would leak straight back through
 * the trade window.
 *
 * <p><b>Plus a permanent stamp.</b> Unlike {@link CheatModIntegrity} this is not purely derived:
 * {@link #refresh} also marks each operator's own run permanently cheated
 * ({@link RunIntegrity#markCheated}). Without that, {@code /op} → cheat yourself a full set →
 * {@code /deop} would launder the run clean. Non-operators are only affected for as long as an
 * operator is present.
 *
 * <p><b>Live, not boot-time.</b> Cheats arrive mid-session — Open to LAN with cheats, {@code /op},
 * a permission plugin — so this cannot be a {@code ServerAboutToStartEvent} scan. It is a throttled
 * {@link ServerTickEvent.Post} sweep ({@value #SWEEP_INTERVAL_TICKS} ticks) plus an explicit
 * {@link #refresh} at login, cached into a volatile snapshot so {@link RunIntegrity#isCheated} —
 * called from ~20 persistence gates — stays O(1).
 *
 * <p>Detection is {@link ServerPlayer#hasPermissions(int)} alone, which already folds together every
 * way a player gets cheats: {@code ops.json} on a dedicated server, a single-player world created
 * with "Allow Cheats" (level 4), and Open to LAN with cheats
 * ({@code PlayerList#setAllowCheatsForAllPlayers}). The world's own {@code allowCommands} flag is
 * deliberately not consulted — it can only be true when the player it applies to is already level 4,
 * so it would add a second source of truth and no coverage.
 *
 * <p>Like every taint source here this is a soft honesty nudge, not hard anti-cheat.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class OperatorIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's "can use cheat commands" threshold — /give, /gamemode, WorldEdit, REI cheat mode. */
    static final int OPERATOR_PERMISSION_LEVEL = 2;

    /** ~1s between sweeps: fast enough that Open to LAN is caught before the player can use it. */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /**
     * Names of the online operators as of the last sweep; empty when clean (or no server running).
     * Immutable snapshot, replaced whole — never mutated (volatile: written on the server thread,
     * read from event handlers).
     */
    private static volatile List<String> detected = List.of();

    /** Server-thread only — {@link ServerTickEvent.Post} is single-threaded. */
    private static int tickCounter;

    private OperatorIntegrity() {}

    /** Is the current server session Free Play because someone online has cheats? */
    public static boolean isSessionFreePlay() {
        return !detected.isEmpty();
    }

    /**
     * The online operators found by the last sweep — shown in the login notice so the player can
     * see exactly WHO tripped Free Play. Empty when clean.
     */
    public static List<String> detected() {
        return detected;
    }

    /**
     * Re-scan the player list, stamp every operator's run, and announce a change of state.
     *
     * <p>Called from the tick sweep and on logout. Use {@link #refreshOnJoin} for a player joining
     * — that caller sends the joiner their own notice.</p>
     */
    public static void refresh(MinecraftServer server) {
        update(server, null);
    }

    /**
     * The same sweep, run as a player joins.
     *
     * <p>Called explicitly (rather than via a second {@code PlayerLoggedInEvent} subscriber) from
     * the top of {@code CheatDetectionEvents.onLogin}, because that handler reads
     * {@link #isSessionFreePlay} to decide whether to send the notice and same-priority handlers in
     * different classes have no guaranteed order. A player is already in the player list by the time
     * that event fires, so a joining operator is counted in their own join's scan — and is skipped
     * by the transition announcement below, since {@code onLogin} tells them directly.</p>
     */
    public static void refreshOnJoin(ServerPlayer joining) {
        update(joining.getServer(), joining);
    }

    /**
     * @param skipAnnounce a player the transition announcement must not reach because their caller
     *                     is telling them already; {@code null} to announce to everyone online
     */
    private static void update(MinecraftServer server, ServerPlayer skipAnnounce) {
        if (server == null) return;
        List<ServerPlayer> operators = scan(server);
        List<String> names = new ArrayList<>(operators.size());
        for (ServerPlayer player : operators) names.add(player.getGameProfile().getName());
        names.sort(String::compareTo);

        // Publish the session state BEFORE stamping: markCheated reads isVisiblySessionFreePlay()
        // (which now includes this source) to decide whether to notify, and the announcement below
        // is the one place this cause gets explained. Stamping first would produce a second,
        // redundant chat line and a Discord post per operator.
        List<String> previous = detected;
        detected = List.copyOf(names);
        boolean wasFreePlay = !previous.isEmpty();
        boolean isFreePlay = !detected.isEmpty();

        if (!previous.equals(detected)) {
            if (isFreePlay) {
                LOGGER.warn("[DungeonTrain] Cheats are available to {} — this session runs in Free Play: {}",
                    detected.size() == 1 ? "a player" : "players", String.join(", ", detected));
            } else {
                LOGGER.info("[DungeonTrain] No operators online — the session-wide Free Play taint has "
                    + "cleared (runs already stamped stay Free Play).");
            }
        }

        for (ServerPlayer player : operators) {
            // Idempotent off the RUN_CHEATED attachment, so this is a no-op after the first sweep.
            // This is the half that survives /deop: the operator's own run stays Free Play.
            RunIntegrity.markCheated(player, Component.translatable(
                "chat.dungeontrain.free_play.cause.operator"));
        }

        if (wasFreePlay == isFreePlay) return; // no state change — nothing to announce or swap

        // Unlike the boot-scanned taints (AIS, DT config, cheat mods) this one flips mid-session:
        // Open to LAN with cheats, /op, /deop. So the effect, the explanation and the Ender Chest
        // slot all have to be brought in line here rather than only at login.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (isFreePlay) {
                    RunIntegrity.applyFreePlayEffect(player);
                    if (player != skipAnnounce) sendNotice(player);
                } else if (!RunIntegrity.isCheated(player)) {
                    // Only players with no taint of their own go back to normal. Operators keep
                    // theirs (the stamp above), and MobEffectEvent.Remove still guards them.
                    player.removeEffect(ModMobEffects.FREE_PLAY);
                    player.sendSystemMessage(Component
                        .translatable("chat.dungeontrain.free_play.operators_cleared")
                        .withStyle(ChatFormatting.GRAY));
                }
                // The Ender Chest slot provider reads RunIntegrity.isCheated live, so the slot this
                // player resolves to has just changed — swap the live chest over now rather than
                // leaving them on the previous one until something else refreshes it.
                EnderChestLockBridge.engage(player);
            } catch (Exception e) {
                // One player's failed swap must not skip everyone after them.
                LOGGER.warn("[DungeonTrain] Free Play operator transition failed for {}",
                    player.getName().getString(), e);
            }
        }
    }

    /**
     * Explain this taint to one player: the standard Free Play notice, then WHO has cheats, then
     * what to do about it. Shared by the mid-session transition above and the login notice in
     * {@code CheatDetectionEvents.onLogin}, so a player gets the same explanation either way.
     *
     * <p>It names the operators because on a shared world that is usually somebody else, and "your
     * progress no longer counts" with no reason attached reads as arbitrary. There is no one-click
     * fix, same as the cheat-mod notice: a world's "Allow Cheats" answer is given when it is
     * created.</p>
     */
    public static void sendNotice(ServerPlayer player) {
        RunIntegrity.sendFreePlayNotice(player,
            Component.translatable("chat.dungeontrain.free_play.cause.operator"));
        player.sendSystemMessage(Component.translatable(
                "chat.dungeontrain.free_play.operators", String.join(", ", detected))
            .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.free_play.operators_fix")
            .withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < SWEEP_INTERVAL_TICKS) return;
        tickCounter = 0;
        refresh(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // The last operator leaving should lift the session taint without waiting for the sweep.
        // The player is already out of the list here, so they aren't counted.
        refresh(event.getEntity().getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        detected = List.of();
        tickCounter = 0;
    }

    /**
     * The online players who have cheats. Wrapped so a broken player list can never take the server
     * down from a tick handler — a scan failure just means "no operators", matching the defensive
     * posture of {@link CheatModIntegrity#scan}.
     */
    static List<ServerPlayer> scan(MinecraftServer server) {
        try {
            List<ServerPlayer> found = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.hasPermissions(OPERATOR_PERMISSION_LEVEL)) found.add(player);
            }
            return found;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not scan the player list for operators — assuming none: {}",
                t.toString());
            return List.of();
        }
    }

    /**
     * Pure: which players have cheats, by name, sorted. Package-visible so the rule can be unit
     * tested without a live server.
     *
     * @param permissionLevels player name → permission level
     */
    static List<String> detectedFrom(Map<String, Integer> permissionLevels) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Integer> e : permissionLevels.entrySet()) {
            Integer level = e.getValue();
            if (e.getKey() != null && level != null && level >= OPERATOR_PERMISSION_LEVEL) {
                found.add(e.getKey());
            }
        }
        found.sort(String::compareTo);
        return List.copyOf(found);
    }

    /** Test seam: force the detected-operator snapshot. {@code null} resets to clean. */
    static void setDetectedForTest(List<String> names) {
        detected = names == null ? List.of() : List.copyOf(names);
    }
}
