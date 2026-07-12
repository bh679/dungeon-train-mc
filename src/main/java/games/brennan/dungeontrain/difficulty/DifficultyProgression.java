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
     * Raw highest signed {@link PlayerRunState#travelledCarriageIndex()} across all
     * currently online players, with <strong>no</strong> difficulty offset applied.
     * Tier math downstream uses {@code abs(...)}, so "max signed" gives the
     * furthest-progressed leader's contribution. Returns 0 when no players are online.
     * The {@code /dungeontrain difficulty} command re-anchors its offset against this
     * raw value.
     */
    public static int rawMaxTravelledCarriageIndex(ServerLevel serverLevel) {
        int max = 0;
        boolean any = false;
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            int t = ModDataAttachments.DT_PLAYER_RUN_STATE.get(player).travelledCarriageIndex();
            if (!any || Math.abs(t) > Math.abs(max)) {
                max = t;
                any = true;
            }
        }
        return max;
    }

    /**
     * Furthest-progressed leader's travelled-carriage value <strong>with the
     * {@link DungeonTrainConfig#getDifficultyTravelledOffset() difficulty offset}
     * applied</strong> ({@link #effectiveTravelled}). This is the single chokepoint
     * every live-progress difficulty consumer reads — mob-gear tier, the onboarding
     * stage gate, the starter-loot window, and villager trade caps — so an admin
     * offset shifts them all together. Entities spawned during world load (no players
     * online) default to the offset applied to 0.
     */
    public static int maxTravelledCarriageIndex(ServerLevel serverLevel) {
        return effectiveTravelled(rawMaxTravelledCarriageIndex(serverLevel));
    }

    /**
     * A raw travelled-carriage value shifted by the configured
     * {@link DungeonTrainConfig#getDifficultyTravelledOffset() difficulty offset} — the
     * game treats the player as having travelled this many extra carriages for all
     * difficulty/HUD/achievement purposes. With the default offset of 0 this returns
     * {@code rawTravelled} unchanged.
     */
    public static int effectiveTravelled(int rawTravelled) {
        return rawTravelled + DungeonTrainConfig.getDifficultyTravelledOffset();
    }

    /**
     * The travelled-carriage offset that makes the effective difficulty tier become
     * exactly {@code requestedTier} right now, given the leader's current
     * {@code rawTravelled}. Solves {@code effectiveTier(rawTravelled + offset) == requestedTier}
     * by targeting the low end of the requested tier's carriage range
     * ({@code (requestedTier + delay) * carriagesPerTier}) and subtracting current raw
     * progress. Pure (params in) so it is unit-testable without a config bootstrap; the
     * command passes the live {@link DungeonTrainConfig#getCarriagesPerTier()} /
     * {@link DungeonTrainConfig#getProgressionLevelDelay()} values.
     */
    public static int travelledOffsetForRequestedTier(int requestedTier, int rawTravelled,
                                                       int carriagesPerTier, int progressionLevelDelay) {
        int target = (requestedTier + Math.max(0, progressionLevelDelay)) * Math.max(1, carriagesPerTier);
        return target - rawTravelled;
    }

    /**
     * Raw geometric difficulty tier for a signed travelled-carriage value, with
     * <strong>no</strong> progression delay applied:
     * {@code abs(travelled) / max(1, carriagesPerTier)}. Pure (params in) so it is
     * unit-testable without a NeoForge config bootstrap. Difficulty scaling keys off
     * {@link #effectiveTier} / {@link #tierForTravelled}; the gentle-onboarding stages
     * ({@link #onboardingStage}) key off raw travelled-carriage counts directly.
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
     * Current difficulty tier: {@link #maxTravelledCarriageIndex} (already
     * offset-inclusive) mapped through {@link #tierForTravelled}. Returns 0 when no
     * players are online and no offset is set.
     */
    public static int currentTier(ServerLevel serverLevel) {
        return tierForTravelled(maxTravelledCarriageIndex(serverLevel));
    }

    /**
     * The template-gate "Diff-Level" of the column at {@code worldX}, derived from position: the
     * carriage-equivalent index there is {@code floorDiv(worldX, carriageLength)} (for a carriage
     * this equals its own pIdx, since the train is anchored at world-X 0), mapped through
     * {@link #positionTier}. Used by the per-template spawn gate
     * ({@link games.brennan.dungeontrain.template.TemplateGate}) so every weighted template kind —
     * carriages, contents, track, pillars, tunnels — shares one position-derived level scale.
     * With no admin difficulty offset this is player-independent and reproducible across reloads;
     * a {@code /dungeontrain difficulty} offset shifts it so the carriage stage that generates
     * ahead re-themes to match (reproducible for a given offset). Reads config via
     * {@link #positionTier}.
     */
    public static int levelAtWorldX(int worldX, int carriageLength) {
        return positionTier(Math.floorDiv(worldX, Math.max(1, carriageLength)));
    }

    /**
     * Effective difficulty tier for a carriage/column at signed {@code positionIndex} (a carriage
     * pIdx or world-X carriage-equivalent), with the
     * {@link DungeonTrainConfig#getDifficultyTravelledOffset() difficulty offset} applied to its
     * absolute distance from the train origin (clamped at 0). This is the <em>position-frame</em>
     * analogue of {@link #effectiveTravelled} (the <em>player-progress</em> frame): it lets an
     * admin difficulty offset shift which stage-gated templates, contents variants, and loot tier a
     * carriage generates with — so cranking difficulty re-themes the carriages generated ahead, not
     * just the mobs riding on them. With the default offset of 0 this is exactly
     * {@code tierForTravelled(abs(positionIndex))} — the original position-derived,
     * reload-reproducible level.
     */
    public static int positionTier(int positionIndex) {
        return tierForTravelled(shiftedPosition(positionIndex, DungeonTrainConfig.getDifficultyTravelledOffset()));
    }

    /**
     * Pure {@code max(0, abs(positionIndex) + offset)} — the offset-shifted absolute carriage
     * distance behind {@link #positionTier}. {@code abs} first (position is a magnitude), then the
     * offset, then a floor at 0 so a negative offset (lowered difficulty) can't wrap a small
     * distance back up into a positive tier. Pure (params in) so it is unit-testable.
     */
    static int shiftedPosition(int positionIndex, int offset) {
        return Math.max(0, Math.abs(positionIndex) + offset);
    }

    /**
     * The three-stage gentle-onboarding ramp a carriage hostile falls into, by the lead player's
     * raw travelled-carriage progress. {@link #NO_HOSTILES} (the opening stretch) suppresses
     * authored hostiles entirely; {@link #EASY_MOBS} (the next stretch) replaces them with small
     * slimes / magma cubes; {@link #NORMAL} spawns them as authored. Keys off raw travelled
     * carriages, so it is independent of {@link DungeonTrainConfig#getProgressionLevelDelay()}.
     */
    public enum OnboardingStage { NO_HOSTILES, EASY_MOBS, NORMAL }

    /**
     * Pure onboarding-stage decision over the lead player's signed {@code travelled} progress and
     * the two stage lengths (in carriages). The no-hostiles stage occupies {@code [0, N)} and the
     * easy-mobs (slimes) stage the {@code E} carriages after it, {@code [N, N+E)}; everything from
     * {@code N+E} on is {@link OnboardingStage#NORMAL}. A disabled stage contributes zero length, so
     * e.g. disabling no-hostiles makes slimes start at carriage 0. Uses {@code abs(travelled)} (so
     * backward travel ramps identically) and clamps negative counts to 0. Pure (params in) so it is
     * unit-testable without a config/level bootstrap.
     */
    static OnboardingStage onboardingStage(int travelled,
                                           boolean noHostilesOn, int noHostilesCarriages,
                                           boolean easyMobsOn, int easyMobsCarriages) {
        int t = Math.abs(travelled);
        int noHostilesEnd = noHostilesOn ? Math.max(0, noHostilesCarriages) : 0;
        if (t < noHostilesEnd) return OnboardingStage.NO_HOSTILES;
        int easyMobsEnd = noHostilesEnd + (easyMobsOn ? Math.max(0, easyMobsCarriages) : 0);
        if (t < easyMobsEnd) return OnboardingStage.EASY_MOBS;
        return OnboardingStage.NORMAL;
    }

    /**
     * Whether {@code travelled} is still inside the gentle opening window — the
     * {@code noHostilesCarriages + easyMobsCarriages} carriages spanning both onboarding stages,
     * regardless of the per-stage toggles. Drives the {@link #effectiveLootPrefabId starter-loot}
     * downgrade so loot stays gentle for as long as the combat intro lasts. Pure (params in).
     */
    static boolean inOnboardingWindow(int travelled, int noHostilesCarriages, int easyMobsCarriages) {
        return Math.abs(travelled) < Math.max(0, noHostilesCarriages) + Math.max(0, easyMobsCarriages);
    }

    /**
     * The {@link OnboardingStage} the run is currently in, reading the live config and the
     * furthest-progressed online player. With no players online {@link #maxTravelledCarriageIndex}
     * is 0, so a default-on config reports {@link OnboardingStage#NO_HOSTILES}.
     */
    public static OnboardingStage onboardingStageFor(ServerLevel serverLevel) {
        return onboardingStage(maxTravelledCarriageIndex(serverLevel),
                DungeonTrainConfig.getFirstLevelNoHostiles(), DungeonTrainConfig.getFirstLevelNoHostilesCarriages(),
                DungeonTrainConfig.getFirstLevelEasyMobs(), DungeonTrainConfig.getFirstLevelEasyMobsCarriages());
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
     * {@link DungeonTrainConfig#getFirstLevelStarterLoot()} is enabled and the run is still inside
     * the gentle opening window ({@link #inOnboardingWindow}); every other id (and null) passes
     * through unchanged.
     */
    public static String effectiveLootPrefabId(ServerLevel serverLevel, String lootPrefabId) {
        return downgradeLootId(lootPrefabId,
                DungeonTrainConfig.getFirstLevelStarterLoot()
                        && inOnboardingWindow(maxTravelledCarriageIndex(serverLevel),
                                DungeonTrainConfig.getFirstLevelNoHostilesCarriages(),
                                DungeonTrainConfig.getFirstLevelEasyMobsCarriages()));
    }

    /** Outcome of the first-band hostile substitution decision: spawn the authored mob unchanged
     *  ({@link #NONE}), or replace it with a small {@link #SLIME} or {@link #MAGMA_CUBE}. */
    public enum FirstBandSubstitute { NONE, SLIME, MAGMA_CUBE }

    /**
     * Pure first-band substitution decision, in precedence order:
     * <ol>
     *   <li>{@code neverSubstitute} mobs (e.g. zombified piglin) always spawn as authored
     *       ({@link FirstBandSubstitute#NONE}) — they're never downgraded, in any dimension.</li>
     *   <li>{@code magmaInNetherOnly} mobs (e.g. piglin / piglin brute) become a magma cube inside
     *       the Nether but spawn as authored ({@link FirstBandSubstitute#NONE}) anywhere else.</li>
     *   <li>Everything else becomes a {@link FirstBandSubstitute#MAGMA_CUBE} when {@code magmaMob},
     *       otherwise a small {@link FirstBandSubstitute#SLIME}.</li>
     * </ol>
     * Pure over its four booleans (tag membership + dimension, resolved by the caller) so it is
     * unit-testable without a level/config bootstrap.
     */
    public static FirstBandSubstitute firstBandSubstitute(boolean neverSubstitute,
                                                          boolean magmaInNetherOnly,
                                                          boolean magmaMob, boolean nether) {
        if (neverSubstitute) return FirstBandSubstitute.NONE;
        if (magmaInNetherOnly && !nether) return FirstBandSubstitute.NONE;
        return magmaMob ? FirstBandSubstitute.MAGMA_CUBE : FirstBandSubstitute.SLIME;
    }
}
