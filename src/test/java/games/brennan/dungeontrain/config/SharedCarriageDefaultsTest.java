package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped shared-carriage config defaults.
 *
 * <p>{@code sharedCarriagesEnabled} is the master gate for both halves of the feature
 * ({@code SharedCarriageGate.canLease} for leasing, {@code canContribute} for uploading), with
 * {@code sharedCarriageLeasingEnabled} a second, narrower gate on the leasing half alone. The master
 * shipped {@code false} through a release that publicly announced the feature, so for a day of
 * live play across 40+ players not a single shared carriage was placed or uploaded anywhere — the
 * code path never executed once, and nothing in the build said so. This test is the tripwire: if
 * the default ever drifts back off, it fails here rather than in production silence.</p>
 *
 * <p>The chance defaults are pinned alongside it because they must leave room for each other —
 * {@code DungeonTrainConfig.getSharedCarriageOwnChance} trims {@code own} to {@code 1 - pool} at
 * read time, so a pair summing above 1.0 would silently reshape the split.</p>
 */
class SharedCarriageDefaultsTest {

    @Test
    @DisplayName("the shared-carriage master default is ON")
    void masterDefaultIsOn() {
        assertTrue(DungeonTrainConfig.DEFAULT_SHARED_CARRIAGES_ENABLED,
                "shared carriages must ship enabled — with this false the feature is inert for every "
                        + "player and no lease or upload can ever happen");
    }

    /**
     * The default flip alone only reaches installs with no config file yet, so it must be paired with
     * a migration step. If someone changes a shipped default in future without bumping this counter,
     * that change silently reaches new installs only — the failure mode this whole test class exists
     * to catch.
     */
    @Test
    @DisplayName("a config migration ships to carry the new default to existing installs")
    void aMigrationShipsForTheNewDefault() {
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION > DungeonTrainConfig.DEFAULT_CONFIG_VERSION,
                "CURRENT_CONFIG_VERSION must exceed the pre-versioning default ("
                        + DungeonTrainConfig.DEFAULT_CONFIG_VERSION + "), or runPendingMigrations() never "
                        + "fires and an existing dungeontrain-server.toml keeps its stale values forever");
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION <= DungeonTrainConfig.MAX_CONFIG_VERSION,
                "CURRENT_CONFIG_VERSION must stay inside the spec's allowed range, or the value fails "
                        + "validation and NeoForge silently resets it to the default");
    }

    @Test
    @DisplayName("pool + own defaults leave a fresh-canvas share")
    void poolAndOwnLeaveRoomForFreshBuilds() {
        double pool = DungeonTrainConfig.DEFAULT_SHARED_CARRIAGE_POOL_CHANCE;
        double own = DungeonTrainConfig.DEFAULT_SHARED_CARRIAGE_OWN_CHANCE;

        assertTrue(pool > 0.0, "pool share must be positive or community builds are never served");
        assertTrue(own > 0.0, "own share must be positive or players never meet their own builds");
        assertTrue(pool + own < 1.0,
                "pool + own must leave a remainder: that remainder is the fresh blank canvas share, "
                        + "and it is the ONLY thing that feeds new builds into the pool (pool=" + pool
                        + ", own=" + own + ")");
    }

    /**
     * Leasing ships ON, and builder submissions ship OFF — the asymmetry that replaced the old one.
     *
     * <p>Asserted as a group rather than one constant each, because it is the COMBINATION that is the
     * product decision. Community carriages — rooms a player changed while riding, which the relay
     * screens and pools as {@code source='play'} — circulate through runs. Train Builder builds are a
     * separate system: they upload to their author's profile and stop there. Leasing off again would
     * empty every shared slot back to blank templates; submissions on would put authored builds into
     * strangers' runs before that half is ready.</p>
     */
    @Test
    @DisplayName("community carriages are served; builder builds are not")
    void communityCarriagesAreServedAndBuilderBuildsAreNot() {
        assertTrue(DungeonTrainConfig.DEFAULT_SHARED_CARRIAGES_ENABLED,
                "the master must stay on or builds stop being uploaded at all");
        assertTrue(DungeonTrainConfig.DEFAULT_SHARED_CARRIAGE_LEASING_ENABLED,
                "leasing must ship on, or a shared slot places a blank template and no community "
                        + "carriage is ever seen by anyone");
        assertFalse(DungeonTrainConfig.DEFAULT_BUILDER_SUBMIT_TO_TRAIN_ENABLED,
                "submitting a builder build to the train must ship off — the relay withholds "
                        + "source='builder' rows from every lease, so an open switch here would only "
                        + "offer players an action that can never take effect");
    }

    /**
     * The leasing flip needs its own migration step, or it reaches new installs only — every install
     * that has launched since the key shipped holds a stored {@code false}.
     */
    @Test
    @DisplayName("the config version records the leasing migration")
    void theLeasingFlipShipsWithItsOwnMigration() {
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION >= 3,
                "flipping DEFAULT_SHARED_CARRIAGE_LEASING_ENABLED needs a runPendingMigrations() step "
                        + "above the previous version (2), or an existing dungeontrain-server.toml keeps "
                        + "leasing off forever and nothing is ever placed");
    }
}
