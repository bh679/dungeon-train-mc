package games.brennan.dungeontrain.cheat;

import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/**
 * World settings that put a run outside the balance Dungeon Train is tuned for,
 * and so mean <b>Free Play</b> (see {@link RunIntegrity}):
 *
 * <ul>
 *   <li>{@code keepInventory} on — death costs nothing, and the stake of a run is
 *       the inventory you lose.</li>
 *   <li>Difficulty below the {@link #DEFAULT_DIFFICULTY default} — {@code EASY} or
 *       {@code PEACEFUL}. Peaceful removes hostile mobs entirely; the train's
 *       difficulty progression has nothing left to scale.</li>
 * </ul>
 *
 * <p>Unlike a cheat command, both are reachable with <b>no command at all</b> — the
 * world-creation Game Rules screen and the in-game difficulty slider — so the
 * {@link CommandAllowlist} gate never sees them. {@link #sweep} is the catch-all:
 * it re-reads the live world settings and marks every online player, and is driven
 * from {@code CheatDetectionEvents} on login and on a ~1&nbsp;s server tick. The
 * difficulty slider itself is intercepted earlier (with a confirm prompt) by
 * {@code ServerGamePacketListenerImplDifficultyMixin}; the sweep still backs it up
 * for paths nothing intercepts ({@code /execute}, another mod, a datapack).</p>
 *
 * <p>The taint is <b>permanent for the run</b>, like every other {@code markCheated}
 * caller: turning the setting back does not restore a clean run.</p>
 */
public final class WorldSettingsIntegrity {

    /**
     * The difficulty a Dungeon Train run is balanced at. Anything below it is Free
     * Play; {@code HARD} (above) is a legitimate, harder run.
     */
    public static final Difficulty DEFAULT_DIFFICULTY = Difficulty.NORMAL;

    private WorldSettingsIntegrity() {}

    /** Is this difficulty below the default, i.e. Free Play? (PEACEFUL, EASY.) */
    public static boolean isFreePlayDifficulty(Difficulty difficulty) {
        return difficulty != null && difficulty.getId() < DEFAULT_DIFFICULTY.getId();
    }

    /** Is {@code keepInventory} on, i.e. Free Play? */
    public static boolean isFreePlayKeepInventory(GameRules rules) {
        return rules != null && rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
    }

    /** The soft cause phrase for a {@code keepInventory} taint. */
    public static Component causeKeepInventory() {
        return Component.translatable("chat.dungeontrain.free_play.cause.keep_inventory");
    }

    /** The soft cause phrase for a below-default difficulty taint. */
    public static Component causeDifficulty(Difficulty difficulty) {
        return Component.translatable("chat.dungeontrain.free_play.cause.difficulty",
            difficulty.getDisplayName());
    }

    /**
     * Re-read the live world settings and, if either trips, mark every online
     * player Free Play. {@link RunIntegrity#markCheated} is idempotent, so calling
     * this every tick costs two field reads once the run is already tainted.
     *
     * <p>{@code keepInventory} is reported ahead of difficulty when both are set —
     * the cause line names one thing, and the one the player chose deliberately is
     * the more useful of the two.</p>
     */
    public static void sweep(MinecraftServer server) {
        if (server == null) return;
        Component cause = cause(server);
        if (cause == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (RunIntegrity.isPermanentlyCheated(player)) continue;
            RunIntegrity.markCheated(player, cause);
            // Mid-session taint (the tick backstop): hide the legit Ender Chest now
            // rather than at the next login. No-op when the slot is unchanged.
            EnderChestLockBridge.engage(player);
        }
    }

    /**
     * The cause phrase for the server's current world settings, or {@code null}
     * when they are clean. Reads {@code WorldData}'s difficulty — the world
     * <em>setting</em> — not {@code Level#getDifficulty}, which reports PEACEFUL
     * for unrelated reasons.
     */
    private static Component cause(MinecraftServer server) {
        if (isFreePlayKeepInventory(server.getGameRules())) return causeKeepInventory();
        Difficulty difficulty = server.getWorldData().getDifficulty();
        if (isFreePlayDifficulty(difficulty)) return causeDifficulty(difficulty);
        return null;
    }
}
