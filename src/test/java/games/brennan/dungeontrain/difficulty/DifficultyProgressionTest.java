package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.difficulty.DifficultyProgression.FirstBandSubstitute;
import games.brennan.dungeontrain.difficulty.DifficultyProgression.OnboardingStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-logic unit tests for {@link DifficultyProgression#rawTier} (geometric tier math)
 * and {@link DifficultyProgression#effectiveTier} (the progression-delay shift). No
 * Forge / NeoForge bootstrap — the methods under test take {@code carriagesPerTier} and
 * {@code delay} as parameters, mirroring the {@code VillagerTrainSpawnLevelTest} pattern
 * of exercising the pure helper rather than the config-reading wrapper.
 */
final class DifficultyProgressionTest {

    /** Default carriagesPerTier (matches {@code DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER}). */
    private static final int CPT = 20;

    @Test
    @DisplayName("rawTier = floor(abs(travelled) / carriagesPerTier)")
    void rawTier_geometric() {
        assertEquals(0, DifficultyProgression.rawTier(0, CPT));
        assertEquals(0, DifficultyProgression.rawTier(19, CPT));
        assertEquals(1, DifficultyProgression.rawTier(20, CPT));
        assertEquals(1, DifficultyProgression.rawTier(39, CPT));
        assertEquals(2, DifficultyProgression.rawTier(40, CPT));
        assertEquals(5, DifficultyProgression.rawTier(100, CPT));
    }

    @Test
    @DisplayName("rawTier uses absolute value (backward travel scales identically)")
    void rawTier_usesAbs() {
        assertEquals(0, DifficultyProgression.rawTier(-19, CPT));
        assertEquals(1, DifficultyProgression.rawTier(-20, CPT));
        assertEquals(2, DifficultyProgression.rawTier(-40, CPT));
    }

    @Test
    @DisplayName("rawTier guards against a zero/negative carriagesPerTier (no div-by-zero)")
    void rawTier_guardsDivisor() {
        assertEquals(20, DifficultyProgression.rawTier(20, 0));
        assertEquals(20, DifficultyProgression.rawTier(20, -5));
    }

    @Test
    @DisplayName("effectiveTier with delay=1 shifts the whole curve back one level")
    void effectiveTier_delayOne() {
        assertEquals(0, DifficultyProgression.effectiveTier(0, CPT, 1));
        assertEquals(0, DifficultyProgression.effectiveTier(19, CPT, 1));
        assertEquals(0, DifficultyProgression.effectiveTier(20, CPT, 1));  // raw 1 → 0
        assertEquals(0, DifficultyProgression.effectiveTier(39, CPT, 1));
        assertEquals(1, DifficultyProgression.effectiveTier(40, CPT, 1));  // raw 2 → 1
        assertEquals(2, DifficultyProgression.effectiveTier(60, CPT, 1));  // raw 3 → 2
    }

    @Test
    @DisplayName("effectiveTier with delay=0 equals rawTier (original curve)")
    void effectiveTier_delayZeroIsRaw() {
        for (int travelled = 0; travelled <= 200; travelled += 7) {
            assertEquals(DifficultyProgression.rawTier(travelled, CPT),
                    DifficultyProgression.effectiveTier(travelled, CPT, 0),
                    "delay=0 should equal rawTier at travelled=" + travelled);
        }
    }

    @Test
    @DisplayName("effectiveTier clamps at 0 — a delay larger than rawTier never goes negative")
    void effectiveTier_clampsAtZero() {
        assertEquals(0, DifficultyProgression.effectiveTier(40, CPT, 5));  // raw 2, delay 5 → 0
        assertEquals(0, DifficultyProgression.effectiveTier(0, CPT, 3));
        assertEquals(0, DifficultyProgression.effectiveTier(19, CPT, 100));
    }

    @Test
    @DisplayName("effectiveTier treats a negative delay as no delay (clamped to 0)")
    void effectiveTier_negativeDelayIsNoDelay() {
        assertEquals(DifficultyProgression.rawTier(60, CPT),
                DifficultyProgression.effectiveTier(60, CPT, -3));
    }

    @Test
    @DisplayName("effectiveTier shifts down by exactly `delay` levels above the floor")
    void effectiveTier_shiftsByDelay() {
        // raw tier 4 (travelled 80): shift by 2 → 2, by 3 → 1, by 4 → 0, by 6 → 0
        assertEquals(2, DifficultyProgression.effectiveTier(80, CPT, 2));
        assertEquals(1, DifficultyProgression.effectiveTier(80, CPT, 3));
        assertEquals(0, DifficultyProgression.effectiveTier(80, CPT, 4));
        assertEquals(0, DifficultyProgression.effectiveTier(80, CPT, 6));
    }

    // --- travelledOffsetForRequestedTier: the command's re-anchor math. Given a requested
    //     difficulty tier and the leader's current RAW travelled carriages, returns the offset
    //     (in carriages) that makes the effective tier exactly the requested value. Verified via
    //     the pure effectiveTier(...) round-trip, mirroring how tierForTravelled reads it live. ---

    /** delay=1 matches DungeonTrainConfig.DEFAULT_PROGRESSION_LEVEL_DELAY. */
    private static final int DELAY = 1;

    private static int offsetFor(int requestedTier, int rawTravelled) {
        return DifficultyProgression.travelledOffsetForRequestedTier(requestedTier, rawTravelled, CPT, DELAY);
    }

    /** effectiveTier of (raw + offset) — what the live tierForTravelled would compute. */
    private static int effectiveTierAfter(int rawTravelled, int offset) {
        return DifficultyProgression.effectiveTier(rawTravelled + offset, CPT, DELAY);
    }

    @Test
    @DisplayName("travelledOffsetForRequestedTier: from raw 0, targets the low end of the tier's carriage band")
    void travelledOffset_fromZero() {
        // target = (tier + delay) * cpt. tier 0 -> (0+1)*20 = 20; tier 10 -> 11*20 = 220.
        assertEquals(20, offsetFor(0, 0));
        assertEquals(220, offsetFor(10, 0));
        assertEquals(520, offsetFor(25, 0));
    }

    @Test
    @DisplayName("travelledOffsetForRequestedTier: round-trips — applying the offset yields exactly the requested tier")
    void travelledOffset_roundTrips() {
        for (int raw = 0; raw <= 400; raw += 13) {
            for (int tier = 0; tier <= 30; tier += 3) {
                int offset = offsetFor(tier, raw);
                assertEquals(tier, effectiveTierAfter(raw, offset),
                    "requested tier " + tier + " at raw " + raw + " should round-trip");
            }
        }
    }

    @Test
    @DisplayName("travelledOffsetForRequestedTier: re-anchoring from nonzero raw subtracts current progress")
    void travelledOffset_reanchorsFromNonzero() {
        // raw 80 (tier 3), request tier 5 -> target (5+1)*20=120, offset = 120-80 = 40.
        assertEquals(40, offsetFor(5, 80));
        assertEquals(5, effectiveTierAfter(80, 40));
        // request a LOWER tier than current -> negative offset. raw 80 (tier 3), request 1 -> target 40, offset -40.
        assertEquals(-40, offsetFor(1, 80));
        assertEquals(1, effectiveTierAfter(80, -40));
    }

    @Test
    @DisplayName("re-anchoring worked example (carriage terms): 0->10, 0->3, travel +40 drifts 3->5, re-anchor to 1")
    void travelledOffset_reanchoringWorkedExample() {
        // Start at raw 0. Request 10 -> offset 220, effective tier 10.
        int offset = offsetFor(10, 0);
        assertEquals(220, offset);
        assertEquals(10, effectiveTierAfter(0, offset));

        // Immediately request 3 (no travel yet) -> re-anchor offset to 80, effective tier 3.
        offset = offsetFor(3, 0);
        assertEquals(80, offset);
        assertEquals(3, effectiveTierAfter(0, offset));

        // Player travels forward two difficulty bands (40 carriages). Offset UNCHANGED at 80.
        // Effective travelled = 40 + 80 = 120 -> tier 5. The effective level drifted 3 -> 5.
        assertEquals(5, effectiveTierAfter(40, offset));

        // Re-anchor to 1 while raw is now 40 -> target (1+1)*20 = 40, offset = 40 - 40 = 0.
        offset = offsetFor(1, 40);
        assertEquals(0, offset);
        assertEquals(1, effectiveTierAfter(40, offset));
    }

    @Test
    @DisplayName("travelledOffsetForRequestedTier: guards a zero/negative carriagesPerTier (no div-by-zero, treats as 1)")
    void travelledOffset_guardsDivisor() {
        // With cpt guarded to 1: target = (tier + delay) * 1.
        assertEquals(3, DifficultyProgression.travelledOffsetForRequestedTier(2, 0, 0, 1));
        assertEquals(3, DifficultyProgression.travelledOffsetForRequestedTier(2, 0, -5, 1));
    }

    // --- shiftedPosition: the position-frame offset shift behind positionTier (carriage template /
    //     contents / loot gating). abs first (position is a magnitude), then offset, floored at 0. ---

    @Test
    @DisplayName("shiftedPosition: zero offset is abs(position) — the original position-derived level")
    void shiftedPosition_zeroOffsetIsAbs() {
        assertEquals(5, DifficultyProgression.shiftedPosition(5, 0));
        assertEquals(5, DifficultyProgression.shiftedPosition(-5, 0));   // abs — backward carriages ramp the same
        assertEquals(0, DifficultyProgression.shiftedPosition(0, 0));
    }

    @Test
    @DisplayName("shiftedPosition: a positive offset pushes the whole position ramp up")
    void shiftedPosition_positiveShiftsUp() {
        assertEquals(105, DifficultyProgression.shiftedPosition(5, 100));
        assertEquals(105, DifficultyProgression.shiftedPosition(-5, 100));
        // near-player carriage (abs ≈ raw travelled) lands on the target band: abs 220 + (80-220) = 80.
        assertEquals(80, DifficultyProgression.shiftedPosition(220, -140));
    }

    @Test
    @DisplayName("shiftedPosition: clamps at 0 — a negative offset can't wrap a small distance into a positive tier")
    void shiftedPosition_clampsAtZero() {
        assertEquals(0, DifficultyProgression.shiftedPosition(5, -100));
        assertEquals(0, DifficultyProgression.shiftedPosition(-5, -100));
    }

    @Test
    @DisplayName("downgradeLootId swaps the rich loot prefabs to starter when active")
    void downgradeLootId_swapsRichWhenActive() {
        assertEquals("starter", DifficultyProgression.downgradeLootId("loot", true));
        assertEquals("starter", DifficultyProgression.downgradeLootId("loot_irongold", true));
    }

    @Test
    @DisplayName("downgradeLootId matches the rich prefab ids case-insensitively")
    void downgradeLootId_caseInsensitive() {
        assertEquals("starter", DifficultyProgression.downgradeLootId("LOOT", true));
        assertEquals("starter", DifficultyProgression.downgradeLootId("Loot_IronGold", true));
    }

    @Test
    @DisplayName("downgradeLootId leaves non-rich prefabs untouched even when active")
    void downgradeLootId_leavesOthersUntouched() {
        assertEquals("wood", DifficultyProgression.downgradeLootId("wood", true));
        assertEquals("mining", DifficultyProgression.downgradeLootId("mining", true));
        assertEquals("villager", DifficultyProgression.downgradeLootId("villager", true));
        // already the starter prefab → unchanged
        assertEquals("starter", DifficultyProgression.downgradeLootId("starter", true));
    }

    @Test
    @DisplayName("downgradeLootId is a no-op when inactive (past the first band / toggle off)")
    void downgradeLootId_noopWhenInactive() {
        assertEquals("loot", DifficultyProgression.downgradeLootId("loot", false));
        assertEquals("loot_irongold", DifficultyProgression.downgradeLootId("loot_irongold", false));
    }

    @Test
    @DisplayName("downgradeLootId is null-safe (empty chest cell, no linked prefab)")
    void downgradeLootId_nullSafe() {
        assertNull(DifficultyProgression.downgradeLootId(null, true));
        assertNull(DifficultyProgression.downgradeLootId(null, false));
    }

    @Test
    @DisplayName("firstBandSubstitute: never-substitute mobs (zombified piglin) spawn as authored in every dimension")
    void firstBandSubstitute_neverSubstitute_noneAnywhere() {
        // zombified piglin: neverSubstitute, and removed from the magma tag (magmaMob=false).
        assertEquals(FirstBandSubstitute.NONE,
                DifficultyProgression.firstBandSubstitute(true, false, false, false)); // overworld
        assertEquals(FirstBandSubstitute.NONE,
                DifficultyProgression.firstBandSubstitute(true, false, false, true));  // Nether
    }

    @Test
    @DisplayName("firstBandSubstitute: nether-only mobs (piglin/brute) spawn as authored outside the Nether")
    void firstBandSubstitute_netherOnly_outsideNether_none() {
        assertEquals(FirstBandSubstitute.NONE,
                DifficultyProgression.firstBandSubstitute(false, true, true, false));
    }

    @Test
    @DisplayName("firstBandSubstitute: nether-only mobs (piglin/brute) become magma cubes in the Nether")
    void firstBandSubstitute_netherOnly_inNether_magma() {
        assertEquals(FirstBandSubstitute.MAGMA_CUBE,
                DifficultyProgression.firstBandSubstitute(false, true, true, true));
    }

    @Test
    @DisplayName("firstBandSubstitute: plain magma mobs (blaze, raiders, ...) become magma cubes in any dimension")
    void firstBandSubstitute_magmaOnly_magmaAnywhere() {
        assertEquals(FirstBandSubstitute.MAGMA_CUBE,
                DifficultyProgression.firstBandSubstitute(false, false, true, false));
        assertEquals(FirstBandSubstitute.MAGMA_CUBE,
                DifficultyProgression.firstBandSubstitute(false, false, true, true));
    }

    @Test
    @DisplayName("firstBandSubstitute: plain hostiles (untagged) become slimes in any dimension")
    void firstBandSubstitute_plainHostile_slimeAnywhere() {
        assertEquals(FirstBandSubstitute.SLIME,
                DifficultyProgression.firstBandSubstitute(false, false, false, false));
        assertEquals(FirstBandSubstitute.SLIME,
                DifficultyProgression.firstBandSubstitute(false, false, false, true));
    }

    @Test
    @DisplayName("firstBandSubstitute: never-substitute takes precedence over the magma / nether-only tags")
    void firstBandSubstitute_neverSubstitute_precedence() {
        // Defensive: even if a mob were also magma- and nether-only-tagged, never-substitute wins.
        assertEquals(FirstBandSubstitute.NONE,
                DifficultyProgression.firstBandSubstitute(true, true, true, true));
        assertEquals(FirstBandSubstitute.NONE,
                DifficultyProgression.firstBandSubstitute(true, true, true, false));
    }

    // --- onboardingStage: the three-stage gentle ramp. Illustrative lengths N=15, E=15; the pure
    //     function is config-agnostic, so these are NOT the shipped defaults (those live in
    //     DungeonTrainConfig and are intentionally tunable). ---

    /** Illustrative no-hostiles stage length (not the shipped config default). */
    private static final int N = 15;
    /** Illustrative slimes (easy-mobs) stage length (not the shipped config default). */
    private static final int E = 15;

    private static OnboardingStage stage(int travelled) {
        return DifficultyProgression.onboardingStage(travelled, true, N, true, E);
    }

    @Test
    @DisplayName("onboardingStage: both stages on → NO_HOSTILES [0,15), EASY_MOBS [15,30), NORMAL [30,∞)")
    void onboardingStage_defaultBoundaries() {
        assertEquals(OnboardingStage.NO_HOSTILES, stage(0));
        assertEquals(OnboardingStage.NO_HOSTILES, stage(14));
        assertEquals(OnboardingStage.EASY_MOBS, stage(15));
        assertEquals(OnboardingStage.EASY_MOBS, stage(29));
        assertEquals(OnboardingStage.NORMAL, stage(30));
        assertEquals(OnboardingStage.NORMAL, stage(100));
    }

    @Test
    @DisplayName("onboardingStage: backward travel uses absolute value (ramps identically)")
    void onboardingStage_usesAbs() {
        assertEquals(OnboardingStage.NO_HOSTILES, stage(-14));
        assertEquals(OnboardingStage.EASY_MOBS, stage(-15));
        assertEquals(OnboardingStage.EASY_MOBS, stage(-29));
        assertEquals(OnboardingStage.NORMAL, stage(-30));
    }

    @Test
    @DisplayName("onboardingStage: both toggles off → always NORMAL")
    void onboardingStage_bothOff() {
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(0, false, N, false, E));
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(50, false, N, false, E));
    }

    @Test
    @DisplayName("onboardingStage: no-hostiles off → slimes start at carriage 0, run for E carriages")
    void onboardingStage_noHostilesOff() {
        assertEquals(OnboardingStage.EASY_MOBS, DifficultyProgression.onboardingStage(0, false, N, true, E));
        assertEquals(OnboardingStage.EASY_MOBS, DifficultyProgression.onboardingStage(14, false, N, true, E));
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(15, false, N, true, E));
    }

    @Test
    @DisplayName("onboardingStage: easy-mobs off → no-hostiles [0,15) then straight to NORMAL")
    void onboardingStage_easyMobsOff() {
        assertEquals(OnboardingStage.NO_HOSTILES, DifficultyProgression.onboardingStage(0, true, N, false, E));
        assertEquals(OnboardingStage.NO_HOSTILES, DifficultyProgression.onboardingStage(14, true, N, false, E));
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(15, true, N, false, E));
    }

    @Test
    @DisplayName("onboardingStage: zero stage lengths → always NORMAL even when toggled on")
    void onboardingStage_zeroLengths() {
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(0, true, 0, true, 0));
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(5, true, 0, true, 0));
    }

    @Test
    @DisplayName("onboardingStage: asymmetric lengths (N=10, E=20) shift the boundaries accordingly")
    void onboardingStage_asymmetric() {
        assertEquals(OnboardingStage.NO_HOSTILES, DifficultyProgression.onboardingStage(9, true, 10, true, 20));
        assertEquals(OnboardingStage.EASY_MOBS, DifficultyProgression.onboardingStage(10, true, 10, true, 20));
        assertEquals(OnboardingStage.EASY_MOBS, DifficultyProgression.onboardingStage(29, true, 10, true, 20));
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(30, true, 10, true, 20));
    }

    @Test
    @DisplayName("onboardingStage: negative stage lengths clamp to 0 (no negative-window crash)")
    void onboardingStage_negativeLengthsClamp() {
        assertEquals(OnboardingStage.NORMAL, DifficultyProgression.onboardingStage(0, true, -5, true, -5));
    }

    @Test
    @DisplayName("inOnboardingWindow: true for the first N+E carriages, false past it")
    void inOnboardingWindow_spansBothStages() {
        assertEquals(true, DifficultyProgression.inOnboardingWindow(0, N, E));
        assertEquals(true, DifficultyProgression.inOnboardingWindow(29, N, E));
        assertEquals(false, DifficultyProgression.inOnboardingWindow(30, N, E));
        assertEquals(false, DifficultyProgression.inOnboardingWindow(100, N, E));
    }

    @Test
    @DisplayName("inOnboardingWindow: uses absolute value and is false for zero-length windows")
    void inOnboardingWindow_absAndZero() {
        assertEquals(true, DifficultyProgression.inOnboardingWindow(-29, N, E));
        assertEquals(false, DifficultyProgression.inOnboardingWindow(-30, N, E));
        assertEquals(false, DifficultyProgression.inOnboardingWindow(0, 0, 0));
    }
}
