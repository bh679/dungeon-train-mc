package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.CheatDetectionEvents;
import games.brennan.dungeontrain.event.DifficultyChangeConfirmEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Holds a <b>world setting</b> written straight to the server — cheats, the world's game mode, the
 * difficulty — until the host has answered DT's confirmation for it.
 *
 * <p><b>Why here.</b> DT's existing prompts sit on commands ({@code CheatDetectionEvents#onCommand})
 * and on the Options-screen difficulty packet. Essential's world panel and vanilla's Open to LAN
 * screen skip both: they call {@code PlayerList#setAllowCommandsForAllPlayers},
 * {@code MinecraftServer#setDefaultGameType} and {@code MinecraftServer#setDifficulty} directly. On
 * 2026-10-23 that is how a player hosting through Essential with cheats on was put into Free Play
 * with no prompt at all — the operator sweep only noticed afterwards. Gating the setters themselves
 * covers Essential, vanilla LAN and any other hosting mod without depending on any of them.</p>
 *
 * <p><b>Who is asked.</b> Only an integrated server has someone to ask: its singleplayer owner (the
 * host). A dedicated server sets these from the console or {@code server.properties}, and its
 * players reach them only through commands, which are already gated — so every check here passes
 * straight through there.</p>
 *
 * <p><b>Threading.</b> Those screens call from the client thread (Essential from its own coroutine
 * dispatcher). Each mixin re-submits a foreign-thread call to the server thread via
 * {@link #onServerThread} and decides there, so {@code PENDING}, the run attachments and the packets
 * are only ever touched on the server thread.</p>
 *
 * <p><b>Re-entry.</b> A confirmed change is applied by calling the same setter again, inside
 * {@link #applying}; the mixins let anything through while that flag is up.</p>
 */
public final class WorldSettingGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Confirm-screen label for turning cheats on (a lang key; the screen translates it). */
    static final String CHEATS_LABEL = "gui.dungeontrain.free_play.trigger.cheats";

    /** Confirm-screen label for changing the world's game mode (a lang key). */
    static final String GAME_MODE_LABEL = "gui.dungeontrain.free_play.trigger.world_gamemode";

    /** Set while DT applies a change it already held and had confirmed. Server thread only. */
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private WorldSettingGate() {}

    /** Is DT itself applying a confirmed change right now (so the setter mixins must let it through)? */
    public static boolean isApplying() {
        return APPLYING.get();
    }

    /** Run {@code change} with the gate open — for applying a change that was already confirmed. */
    public static void applying(Runnable change) {
        boolean outer = APPLYING.get();
        APPLYING.set(Boolean.TRUE);
        try {
            change.run();
        } finally {
            APPLYING.set(outer);
        }
    }

    /**
     * True when this call is already on the server thread. Otherwise {@code retry} (the same setter
     * call) is queued onto the server thread and false is returned: the mixin cancels this call and
     * decides when the retry arrives.
     */
    public static boolean onServerThread(MinecraftServer server, Runnable retry) {
        if (server.isSameThread()) return true;
        server.execute(retry);
        return false;
    }

    /**
     * The player to ask about a world setting: the integrated server's owner, when online. Null on a
     * dedicated server, or before the owner has joined (world load), when there is no one to ask.
     */
    @Nullable
    public static ServerPlayer host(MinecraftServer server) {
        if (server.isDedicatedServer()) return null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.isSingleplayerOwner(player.getGameProfile())) return player;
        }
        return null;
    }

    /**
     * Should a setter call be held for a confirmation? Pure, for unit tests.
     *
     * @param taints          does the new value make the run Free Play (cheats on, creative/spectator)
     * @param hostOnline      is there a host to ask ({@link #host} non-null)
     * @param hostCheated     is the host's run already permanently Free Play
     * @param applyingConfirmed is DT applying a change it already had confirmed
     */
    static boolean shouldHold(boolean taints, boolean hostOnline, boolean hostCheated, boolean applyingConfirmed) {
        return taints && hostOnline && !hostCheated && !applyingConfirmed;
    }

    /** Creative and spectator make a run Free Play on their own; survival and adventure do not. */
    static boolean isTaintingMode(@Nullable GameType mode) {
        return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
    }

    /**
     * Cheats being turned on. Returns true when the call was held (the mixin cancels it); on
     * confirm, {@code apply} re-runs it with the gate open.
     */
    public static boolean holdCheats(MinecraftServer server, boolean allow, Runnable apply) {
        ServerPlayer host = host(server);
        if (!shouldHold(allow, host != null, host != null && RunIntegrity.isPermanentlyCheated(host), isApplying())) {
            return false;
        }
        LOGGER.info("[DungeonTrain] Held cheats being turned on until {} confirms Free Play",
            host.getName().getString());
        CheatDetectionEvents.requestFreePlayConfirm(host, CHEATS_LABEL,
            Component.translatable("chat.dungeontrain.free_play.cause.cheats"),
            () -> applying(() -> {
                apply.run();
                // Vanilla's publishServer sends the new op level right after this call; a held call
                // missed that, so send every player's level (and command tree) now.
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    server.getPlayerList().sendPlayerPermissionLevel(player);
                }
            }));
        return true;
    }

    /**
     * The world's game mode being set to {@code mode}. Returns true when held; on confirm,
     * {@code apply} re-runs it with the gate open.
     */
    public static boolean holdGameMode(MinecraftServer server, @Nullable GameType mode, Runnable apply) {
        ServerPlayer host = host(server);
        if (!shouldHold(isTaintingMode(mode), host != null,
                host != null && RunIntegrity.isPermanentlyCheated(host), isApplying())) {
            return false;
        }
        LOGGER.info("[DungeonTrain] Held the world game mode change to {} until {} confirms Free Play",
            mode.getName(), host.getName().getString());
        CheatDetectionEvents.requestFreePlayConfirm(host, GAME_MODE_LABEL,
            Component.translatable("chat.dungeontrain.free_play.cause.gamemode", mode.getLongDisplayName()),
            () -> applying(apply));
        return true;
    }

    /**
     * The difficulty being set to {@code to}. Not a Free Play cause: it routes to DT's own difficulty
     * confirmation ({@link DifficultyChangeConfirmEvents#request}), which already decides whether the
     * change is worth asking about. Returns true when held.
     */
    public static boolean holdDifficulty(MinecraftServer server, Difficulty to, boolean force) {
        if (isApplying()) return false;
        ServerPlayer host = host(server);
        if (host == null) return false;
        return DifficultyChangeConfirmEvents.request(host, to, force, false);
    }
}
