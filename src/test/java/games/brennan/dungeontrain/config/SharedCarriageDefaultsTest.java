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
