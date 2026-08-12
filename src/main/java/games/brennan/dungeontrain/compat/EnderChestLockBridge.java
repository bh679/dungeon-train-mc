package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.player.EnderChestLabel;
import games.brennan.enderchestpersistence.EnderChestStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Decides which Ender Chest a player is looking at: it locks a Free Play (cheated) run onto the
 * creative-mode slot, so cheated items can never reach a legit chest, and it scopes the legit chest to
 * the player's <em>difficulty profile</em>, so each vanilla difficulty stashes separately.
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
     * Register the slot provider with ECP. Call once from common setup. The provider answers both
     * questions ECP's slot key has to encode, in priority order:
     *
     * <ol>
     *   <li>a cheated run is locked onto the creative slot, whatever else is true;</li>
     *   <li>otherwise the game-mode slot is scoped to the player's difficulty profile, so each vanilla
     *       difficulty keeps its own Ender Chest (config {@code difficultyIsolatedStash}).</li>
     * </ol>
     *
     * <p>Free Play deliberately wins: a cheated run must not be able to read or write ANY legit chest,
     * including the difficulty-scoped ones. And because {@code DifficultyPartition} gives Normal an empty
     * suffix, a Normal profile resolves to exactly the key it used before this existed — every current
     * stash stays where it is.</p>
     */
    public static void install() {
        EnderChestStore.registerSlotProvider((player, defaultKey) -> {
            if (RunIntegrity.isCheated(player)) {
                return FREE_PLAY_SLOT;
            }
            // activeKeyFor folds in the config gate, so the chest, the loadout locker and the advancement
            // sidecar can never disagree about which profile is current.
            String suffix = DifficultyPartition.suffixFor(DifficultyPartition.activeKeyFor(player.level()));
            return suffix.isEmpty() ? null : defaultKey + suffix;
        });
        active = true;
        LOGGER.info("[DungeonTrain] Ender Chest slots installed — Free Play uses '{}'; "
                + "difficulty-isolated stash: {}.",
            FREE_PLAY_SLOT, DungeonTrainConfig.getDifficultyIsolatedStash());
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

    /** Whether a seam-capable ECP is present and the slot provider is registered. */
    public static boolean isActive() {
        return active;
    }

    /**
     * The title to show when {@code player} opens an Ender Chest, or empty to leave vanilla's alone —
     * see {@link EnderChestLabel}.
     *
     * <p>Built from the same inputs as the slot-key provider above, deliberately: derive the label from
     * anything else and it could name a chest other than the one being opened. Empty whenever the
     * provider isn't registered, since without ECP there is only ever the one vanilla chest and a label
     * would be describing a split that doesn't exist.</p>
     */
    public static Optional<Component> titleFor(ServerPlayer player, Component baseTitle) {
        if (!active) {
            return Optional.empty();
        }
        return EnderChestLabel.titleFor(
            baseTitle,
            RunIntegrity.isCheated(player),
            player.gameMode.getGameModeForPlayer(),
            player.level().getDifficulty(),
            DungeonTrainConfig.getDifficultyIsolatedStash());
    }
}
