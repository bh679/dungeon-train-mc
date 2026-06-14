package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Set;

/**
 * Shared difficulty-progression math: how far the current run has progressed,
 * used to pick a {@link DifficultyTier}. Extracted from
 * {@code MobDifficultyEvents} so both the carriage-mob applier and the PlayerMob
 * group spawner scale off the same value ("same difficulty scale as mobs").
 */
public final class DifficultyProgression {

    /** Loot prefab id handed out in place of the rich treasure prefabs during the first band. */
    private static final String STARTER_LOOT_PREFAB_ID = "starter";

    /**
     * Rich treasure loot prefab ids downgraded to {@link #STARTER_LOOT_PREFAB_ID} while the run
     * is still in the first band. Lower-cased for case-insensitive matching (ids are normalized
     * to lower case by {@code LootPrefabStore.load}).
     */
    private static final Set<String> FIRST_BAND_DOWNGRADE_LOOT_IDS = Set.of("loot", "loot_irongold");

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
     * Raw geometric difficulty tier for a signed travelled-carriage value, with
     * <strong>no</strong> progression delay applied:
     * {@code abs(travelled) / max(1, carriagesPerTier)}. Pure (params in) so it is
     * unit-testable without a NeoForge config bootstrap. The first-level-peace gate
     * ({@link #firstLevelPeaceful}) keys off this raw tier; difficulty scaling keys
     * off {@link #effectiveTier} / {@link #tierForTravelled}.
     */
    static int rawTier(int travelled, int carriagesPerTier) {
        return Math.abs(travelled) / Math.max(1, carriagesPerTier);
    }

    /**
     * Effective difficulty tier: {@code max(0, rawTier(travelled) - delay)}. Shifting
     * the raw tier down by {@code delay} levels delays the whole progression curve
     * (mob gear, potion effects, villager trade caps, boarding HUD) by that many
     * levels. Pure (params in) so it is unit-testable.
     */
    static int effectiveTier(int travelled, int carriagesPerTier, int delay) {
        return Math.max(0, rawTier(travelled, carriagesPerTier) - Math.max(0, delay));
    }

    /**
     * Difficulty tier for a signed travelled-carriage value, delayed by the configured
     * {@link DungeonTrainConfig#getProgressionLevelDelay()} levels. This is the
     * boarding-HUD "Diff-Level" and the value {@link DifficultyApplier} and
     * {@code BoardingProgressEvents} derive — centralized here so every consumer shares
     * one (delayed) formula.
     */
    public static int tierForTravelled(int travelled) {
        return effectiveTier(travelled,
                DungeonTrainConfig.getCarriagesPerTier(),
                DungeonTrainConfig.getProgressionLevelDelay());
    }

    /**
     * Current difficulty tier: {@link #maxTravelledCarriageIndex} mapped through
     * {@link #tierForTravelled}. Returns 0 when no players are online.
     */
    public static int currentTier(ServerLevel serverLevel) {
        return tierForTravelled(maxTravelledCarriageIndex(serverLevel));
    }

    /**
     * Whether the furthest-progressed online player is still within the first <em>raw</em> tier
     * band — the original first {@code carriagesPerTier} carriages. Uses the raw, un-delayed tier
     * so the first-band features (peaceful spawns, starter loot) are independent of the
     * progression delay. With no players online {@link #maxTravelledCarriageIndex} is 0 → raw
     * tier 0 → in the first band.
     */
    public static boolean inFirstBand(ServerLevel serverLevel) {
        return rawTier(maxTravelledCarriageIndex(serverLevel),
                       DungeonTrainConfig.getCarriagesPerTier()) == 0;
    }

    /**
     * Whether authored hostile carriage mobs should currently be replaced with small
     * slimes / magma cubes: true when {@link DungeonTrainConfig#getFirstLevelEasyMobs()}
     * is enabled and the run is still {@link #inFirstBand}.
     */
    public static boolean firstLevelEasyMobs(ServerLevel serverLevel) {
        return DungeonTrainConfig.getFirstLevelEasyMobs() && inFirstBand(serverLevel);
    }

    /**
     * Pure first-band loot downgrade: when {@code downgradeActive} and {@code lootPrefabId} is one
     * of the rich {@link #FIRST_BAND_DOWNGRADE_LOOT_IDS} (case-insensitive), returns
     * {@link #STARTER_LOOT_PREFAB_ID}; otherwise returns {@code lootPrefabId} unchanged. Null-safe.
     * Package-visible so it is unit-testable without a config/level bootstrap.
     */
    static String downgradeLootId(String lootPrefabId, boolean downgradeActive) {
        if (lootPrefabId != null
                && downgradeActive
                && FIRST_BAND_DOWNGRADE_LOOT_IDS.contains(lootPrefabId.toLowerCase(Locale.ROOT))) {
            return STARTER_LOOT_PREFAB_ID;
        }
        return lootPrefabId;
    }

    /**
     * The loot prefab id a carriage chest should actually roll: the rich treasure prefabs
     * ("loot", "loot_irongold") are swapped for the "starter" prefab when
     * {@link DungeonTrainConfig#getFirstLevelStarterLoot()} is enabled and the run is still
     * {@link #inFirstBand}; every other id (and null) passes through unchanged.
     */
    public static String effectiveLootPrefabId(ServerLevel serverLevel, String lootPrefabId) {
        return downgradeLootId(lootPrefabId,
                DungeonTrainConfig.getFirstLevelStarterLoot() && inFirstBand(serverLevel));
    }

    /** Outcome of the first-band hostile substitution decision: spawn the authored mob unchanged
     *  ({@link #NONE}), or replace it with a small {@link #SLIME} or {@link #MAGMA_CUBE}. */
    public enum FirstBandSubstitute { NONE, SLIME, MAGMA_CUBE }

    /**
     * Pure first-band substitution decision. Piglin-family mobs only substitute (to a magma cube)
     * inside the Nether; outside the Nether they spawn as authored ({@link FirstBandSubstitute#NONE}),
     * because a magma cube is a Nether creature and a real piglin would zombify in the overworld.
     * Every other hostile becomes a {@link FirstBandSubstitute#MAGMA_CUBE} when magma-tagged,
     * otherwise a {@link FirstBandSubstitute#SLIME}. Pure over its three booleans (tag membership +
     * dimension, resolved by the caller) so it is unit-testable without a level/config bootstrap.
     */
    public static FirstBandSubstitute firstBandSubstitute(boolean piglinFamily, boolean magmaMob, boolean nether) {
        if (piglinFamily && !nether) return FirstBandSubstitute.NONE;
        return magmaMob ? FirstBandSubstitute.MAGMA_CUBE : FirstBandSubstitute.SLIME;
    }
}
