package games.brennan.dungeontrain.cheat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainIntegrityTest {

    @Test
    void defaultDungeonTrainTerrainIsNotFreePlay() {
        assertTrue(TerrainIntegrity.isDefaultTerrain(ResourceLocation.parse("dungeontrain:overworld")));
    }

    @Test
    void compatibleTerrainIsFreePlay() {
        assertFalse(TerrainIntegrity.isDefaultTerrain(ResourceLocation.parse("minecraft:overworld")));
    }

    @Test
    void floorPresetIsFreePlay() {
        assertFalse(TerrainIntegrity.isDefaultTerrain(ResourceLocation.parse("dungeontrain:overworld_y80")));
    }

    @Test
    void nonNoiseGeneratorIsFreePlay() {
        assertFalse(TerrainIntegrity.isDefaultTerrain(null));
    }
}
