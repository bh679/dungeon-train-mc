package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped lengths of the two gentle-onboarding stages.
 *
 * <p>They are the first thing a new player experiences: {@code firstLevelNoHostilesCarriages}
 * carriages with authored hostiles suppressed entirely, then {@code firstLevelEasyMobsCarriages}
 * carriages where they are replaced by small slimes. Together they also set the starter-loot window
 * ({@code DifficultyProgression.inOnboardingWindow}), so the pair decides how long the opening stays
 * quiet in both combat and loot.</p>
 *
 * <p>Shortened from 10 + 15 to 5 + 5: 25 carriages of no real fight was too long a nothing before the
 * game starts.</p>
 */
class OnboardingDefaultsTest {

    @Test
    @DisplayName("the onboarding stages ship at 5 + 5 carriages")
    void stagesShipShort() {
        assertEquals(5, DungeonTrainConfig.DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES,
                "no-hostiles runs carriages 0-5; changing this changes how long the game opens quiet");
        assertEquals(5, DungeonTrainConfig.DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES,
                "slimes run carriages 5-10, i.e. authored hostiles start at carriage 10");
    }

    /**
     * Unlike the shared-carriage flips, these keys are governed by {@code DtConfigIntegrity}: a file
     * still holding the old lengths reads as a deviation and costs the player their advancements. The
     * default change therefore reaches existing installs only via the migration, and the integrity
     * check only forgives the stale value while the file records a version below this one — both
     * halves key off this constant.
     */
    @Test
    @DisplayName("a config migration ships to carry the new lengths to existing installs")
    void aMigrationShipsForTheNewLengths() {
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION
                        >= DungeonTrainConfig.ONBOARDING_LENGTHS_CONFIG_VERSION,
                "shortening the onboarding stages needs a runPendingMigrations() step at version "
                        + DungeonTrainConfig.ONBOARDING_LENGTHS_CONFIG_VERSION + ", or an existing "
                        + "dungeontrain-server.toml keeps 10 + 15 forever AND reads as a cheated "
                        + "config, dropping every existing player into Free Play");
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION <= DungeonTrainConfig.MAX_CONFIG_VERSION,
                "CURRENT_CONFIG_VERSION must stay inside the spec's allowed range, or the value fails "
                        + "validation and NeoForge silently resets it to the default");
    }

    /**
     * The migration only moves a length that still holds the old shipped number, so the two constants
     * have to stay distinguishable — if a new default ever equalled its legacy value the step would be
     * a silent no-op and this pin would be the only thing that noticed.
     */
    @Test
    @DisplayName("the legacy lengths differ from the shipped ones, so the migration has work to do")
    void legacyLengthsAreDistinct() {
        assertEquals(10, DungeonTrainConfig.LEGACY_FIRST_LEVEL_NO_HOSTILES_CARRIAGES);
        assertEquals(15, DungeonTrainConfig.LEGACY_FIRST_LEVEL_EASY_MOBS_CARRIAGES);
        assertTrue(DungeonTrainConfig.LEGACY_FIRST_LEVEL_NO_HOSTILES_CARRIAGES
                        != DungeonTrainConfig.DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES
                || DungeonTrainConfig.LEGACY_FIRST_LEVEL_EASY_MOBS_CARRIAGES
                        != DungeonTrainConfig.DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES,
                "a legacy value equal to the shipped one makes the v3->v4 migration step a no-op");
    }

    /** The stage lengths must sit inside the spec's own range or NeoForge resets them on load. */
    @Test
    @DisplayName("both lengths are inside the spec range")
    void lengthsAreInRange() {
        for (int length : new int[]{
                DungeonTrainConfig.DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES,
                DungeonTrainConfig.DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES}) {
            assertTrue(length >= DungeonTrainConfig.MIN_ONBOARDING_STAGE_CARRIAGES
                            && length <= DungeonTrainConfig.MAX_ONBOARDING_STAGE_CARRIAGES,
                    "out-of-range length " + length + " would be silently replaced by the default");
        }
    }
}
