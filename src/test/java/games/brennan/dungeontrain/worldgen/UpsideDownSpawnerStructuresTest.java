package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spawner structures kept out of the upside-down band — and what is deliberately left in. */
final class UpsideDownSpawnerStructuresTest {

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    @Test
    @DisplayName("trial chambers and mineshafts are out; strongholds and spawner-free structures stay")
    void structures() {
        assertTrue(UpsideDownSpawnerStructures.excludesStructure(mc("trial_chambers")));
        assertTrue(UpsideDownSpawnerStructures.excludesStructure(mc("mineshaft")));
        assertTrue(UpsideDownSpawnerStructures.excludesStructure(mc("mineshaft_mesa")));
        assertFalse(UpsideDownSpawnerStructures.excludesStructure(mc("stronghold")), "End portal / stronghold rings");
        assertFalse(UpsideDownSpawnerStructures.excludesStructure(mc("ancient_city")));
        assertFalse(UpsideDownSpawnerStructures.excludesStructure(mc("village_plains")));
        assertFalse(UpsideDownSpawnerStructures.excludesStructure(null));
    }

    @Test
    @DisplayName("dungeons are out; geodes and fossils stay")
    void features() {
        assertTrue(UpsideDownSpawnerStructures.excludesFeature(mc("monster_room")));
        assertTrue(UpsideDownSpawnerStructures.excludesFeature(mc("monster_room_deep")));
        assertFalse(UpsideDownSpawnerStructures.excludesFeature(mc("amethyst_geode")));
        assertFalse(UpsideDownSpawnerStructures.excludesFeature(mc("fossil_upper")));
        assertFalse(UpsideDownSpawnerStructures.excludesFeature(null));
    }

    /** A stretch covering world-X [1000, 5000). */
    private static boolean inStretch(int x) {
        return x >= 1000 && x < 5000;
    }

    @Test
    @DisplayName("chunks inside the stretch are vetoed")
    void insideCovered() {
        assertTrue(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 3008));
        assertTrue(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 992),
            "a chunk straddling the edge");
    }

    @Test
    @DisplayName("the veto reaches MARGIN_BLOCKS past either end, and no further")
    void marginBothEnds() {
        int m = UpsideDownSpawnerStructures.MARGIN_BLOCKS;
        // After the end (the Reassembly side on the forward run).
        assertTrue(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 5000 + m - 16));
        assertTrue(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 4992 + m));
        assertFalse(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 5008 + m));
        // Before the start (the Reassembly side on the reversed run).
        assertTrue(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 1000 - m));
        assertFalse(UpsideDownSpawnerStructures.coversChunk(UpsideDownSpawnerStructuresTest::inStretch, 1000 - m - 32));
    }
}
