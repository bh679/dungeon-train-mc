package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.enderchestpersistence.EnderChestStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

/**
 * Locks a Free Play (cheated) run's Ender Chest onto the creative-mode slot, so
 * cheated items can never reach the player's legit survival/adventure chest and a
 * Free Play run can't read or modify the real one.
 *
 * <p>The bundled EnderChestPersistence (ECP) mod already keeps a separate Ender
 * Chest per game mode. This bridge registers a {@link EnderChestStore.SlotKeyProvider}
 * that forces the slot to {@link GameType#CREATIVE}'s key whenever
 * {@link RunIntegrity#isCheated} is true — independent of the player's actual game
 * mode, so a survival-mode Free Play (e.g. via {@code /give}) is isolated too. In
 * Dungeon Train, being in creative <em>always</em> implies Free Play, so the
 * creative slot is only ever used by cheated runs anyway.</p>
 *
 * <p>{@link #engage} drives ECP's immediate live swap when a run trips Free Play
 * mid-session, so the legit chest is hidden the instant the run is tainted rather
 * than only on the next login / game-mode change.</p>
 *
 * <p>The hard reference to ECP's seam lives only inside {@link #install()} and
 * {@link #engage} (guarded by {@link #active}), mirroring
 * {@link PlayerMobSocialBridge}: the caller gates on {@code ModList.isLoaded} and
 * catches {@link Throwable}, so a bundled ECP build predating the seam degrades to
 * "no Ender Chest lock" instead of crashing mod load.</p>
 */
public final class EnderChestLockBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The ECP slot key a Free Play run is locked onto (the creative chest). */
    private static final String FREE_PLAY_SLOT = GameType.CREATIVE.getSerializedName();

    /** True once the provider is registered against a seam-capable ECP build. */
    private static volatile boolean active = false;

    private EnderChestLockBridge() {}

    /**
     * Register the slot-lock provider with ECP. Call once from common setup. The
     * provider returns the creative slot key for cheated runs and {@code null}
     * (defer to the game-mode default) otherwise.
     */
    public static void install() {
        EnderChestStore.registerSlotProvider((player, defaultKey) ->
            RunIntegrity.isCheated(player) ? FREE_PLAY_SLOT : null);
        active = true;
        LOGGER.info("[DungeonTrain] Ender Chest lock installed — Free Play runs use the '{}' slot.",
            FREE_PLAY_SLOT);
    }

    /**
     * Immediately swap the player's live Ender Chest onto the locked slot if their
     * run has just become Free Play. Safe to call whenever the provider is active —
     * ECP's {@code refreshSlot} is a no-op when the effective slot is unchanged.
     */
    public static void engage(ServerPlayer player) {
        if (!active) return;
        EnderChestStore.refreshSlot(player);
    }
}
