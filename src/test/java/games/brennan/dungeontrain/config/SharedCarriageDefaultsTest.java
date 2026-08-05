package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped shared-carriage config defaults.
 *
 * <p>{@code sharedCarriagesEnabled} is the <b>sole</b> gate for both halves of the feature
 * ({@code SharedCarriageGate.canDiscover} for leasing, {@code canContribute} for uploading). It
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
}
