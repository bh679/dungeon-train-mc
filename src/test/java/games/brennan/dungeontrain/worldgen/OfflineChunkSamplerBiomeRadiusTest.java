package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins vanilla's rule behind {@link OfflineChunkSampler#biomeInSample}: reading a biome past this radius makes
 * {@code WorldGenRegion} build a crash report (~0.5 s each) just to throw. If a Minecraft update moves the
 * radius, the sampler must follow it.
 */
class OfflineChunkSamplerBiomeRadiusTest {

    private static final ChunkDependencies FEATURES =
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).directDependencies();

    @Test
    @DisplayName("chunks up to the sampler's biome radius have biomes during decoration")
    void chunksInsideTheRadiusHaveBiomes() {
        for (int distance = 0; distance <= OfflineChunkSampler.BIOME_RADIUS; distance++) {
            assertTrue(ChunkStatus.BIOMES.isOrBefore(FEATURES.get(distance)), "distance " + distance);
        }
    }

    @Test
    @DisplayName("the first chunk past the sampler's biome radius has none, so reading it would throw")
    void chunksPastTheRadiusHaveNoBiomes() {
        assertFalse(ChunkStatus.BIOMES.isOrBefore(FEATURES.get(OfflineChunkSampler.BIOME_RADIUS + 1)));
    }
}
