package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared difficulty-progression math: how far the current run has progressed,
 * used to pick a {@link DifficultyTier}. Extracted from
 * {@code MobDifficultyEvents} so both the carriage-mob applier and the PlayerMob
 * group spawner scale off the same value ("same difficulty scale as mobs").
 */
public final class DifficultyProgression {

    private DifficultyProgression() {}

    /**
     * Highest signed {@link PlayerRunState#travelledCarriageIndex()} across all
     * currently online players. Tier math downstream uses {@code abs(...)}, so
     * "max signed" gives the furthest-progressed leader's contribution. Returns
     * 0 when no players are online — entities spawned during world load default
     * to tier 0 (vanilla baseline).
     */
    public static int maxTravelledCarriageIndex(ServerLevel serverLevel) {
        int max = 0;
        boolean any = false;
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            int t = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).travelledCarriageIndex();
            if (!any || Math.abs(t) > Math.abs(max)) {
                max = t;
                any = true;
            }
        }
        return max;
    }

    /**
     * Difficulty tier for a raw signed travelled-carriage value:
     * {@code abs(travelled) / max(1, carriagesPerTier)}. This is the boarding-HUD
     * tier and the value {@link DifficultyApplier} and {@code BoardingProgressEvents}
     * derive — centralized here so every consumer shares one formula.
     */
    public static int tierForTravelled(int travelled) {
        return Math.abs(travelled) / Math.max(1, DungeonTrainConfig.getCarriagesPerTier());
    }

    /**
     * Current difficulty tier: {@link #maxTravelledCarriageIndex} mapped through
     * {@link #tierForTravelled}. Returns 0 when no players are online.
     */
    public static int currentTier(ServerLevel serverLevel) {
        return tierForTravelled(maxTravelledCarriageIndex(serverLevel));
    }
}
