package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link NetherMobSpawner#allowsGlowSquid}: the Nether band's lush caves waive vanilla's
 * y-30 height rule for glow squid, and nothing else. No NeoForge bootstrap, the predicate takes plain flags.
 */
final class NetherGlowSquidSpawnTest {

    @Test
    @DisplayName("dark water in a band lush cave: allowed")
    void bandLushCaveDarkWater_allowed() {
        assertTrue(NetherMobSpawner.allowsGlowSquid(true, true, true, 0));
    }

    @Test
    @DisplayName("outside the band: left to vanilla")
    void outsideBand_refused() {
        assertFalse(NetherMobSpawner.allowsGlowSquid(false, true, true, 0));
    }

    @Test
    @DisplayName("any other cave biome: left to vanilla")
    void otherCave_refused() {
        assertFalse(NetherMobSpawner.allowsGlowSquid(true, false, true, 0));
    }

    @Test
    @DisplayName("out of water: refused")
    void notWater_refused() {
        assertFalse(NetherMobSpawner.allowsGlowSquid(true, true, false, 0));
    }

    @Test
    @DisplayName("any light at all: refused, as vanilla does")
    void lit_refused() {
        assertFalse(NetherMobSpawner.allowsGlowSquid(true, true, true, 1));
        assertFalse(NetherMobSpawner.allowsGlowSquid(true, true, true, 15));
    }
}
