package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import java.util.TreeMap;

/**
 * Debug-only views of what a dimensional carriage's sample would be, for
 * {@code /dungeontrain debug portal-sites}: the biome a site gets from the generator the room is cut
 * with, and a site's generated ground summarised — so the vanilla and Better looks can be told apart
 * from a log line without a client.
 */
public final class PortalSampleProbe {

    private PortalSampleProbe() {}

    /** The level and generator {@code source} is sampled from, or {@code null} if it has none. */
    private static NoiseBasedChunkGenerator generatorFor(ServerLevel level, PortalChunkTerrain.Source source) {
        ChunkGenerator live = level.getChunkSource().getGenerator();
        if (!(live instanceof NoiseBasedChunkGenerator noise)) return null;
        return SampleGenerators.forSource(level, source, noise);
    }

    /** The level {@code source} samples. */
    public static ServerLevel levelFor(MinecraftServer server, PortalChunkTerrain.Source source) {
        return server.getLevel(source.levelKey());
    }

    /** Namespace of the biome the room's own generator puts at the middle of {@code site}. */
    public static String sampledBiomeNamespace(ServerLevel level, PortalChunkTerrain.Source source, ChunkPos site) {
        NoiseBasedChunkGenerator generator = generatorFor(level, source);
        if (generator == null) return null;
        return PortalChunkTerrain.biomeNamespaceAt(generator.getBiomeSource(),
            level.getChunkSource().randomState().sampler(), site);
    }

    /**
     * Generate {@code site}'s ground (terrain and surface rules, no decoration) with the generator
     * {@code source} is cut with, and summarise it: solid blocks, the spread of surface heights, and
     * the chunk's biomes. Runs a whole chunk of generation — a debug command's cost, not a tick's.
     */
    public static String describeGround(ServerLevel level, PortalChunkTerrain.Source source, ChunkPos site) {
        NoiseBasedChunkGenerator generator = generatorFor(level, source);
        if (generator == null) return "no noise generator";
        RandomState random = level.getChunkSource().randomState();
        ProtoChunk chunk = OfflineChunkSampler.blankSample(level, generator, random, site);
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(level, generator, random, chunk);
        ProtoChunk ground = OfflineChunkSampler.fillGround(level, generator, random, chunk, workspace);
        if (ground == null) return "generation failed";

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int solid = 0;
        int columns = 0;
        int lowTop = Integer.MAX_VALUE;
        int highTop = Integer.MIN_VALUE;
        Map<String, Integer> blocks = new TreeMap<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    var state = ground.getBlockState(cursor.set(site.getMinBlockX() + x, y, site.getMinBlockZ() + z));
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    solid++;
                    top = y;
                    blocks.merge(state.getBlock().builtInRegistryHolder().key().location().getPath(), 1, Integer::sum);
                }
                if (top != Integer.MIN_VALUE) {
                    columns++;
                    lowTop = Math.min(lowTop, top);
                    highTop = Math.max(highTop, top);
                }
            }
        }
        Map<String, Integer> biomes = new TreeMap<>();
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                var biome = ground.getNoiseBiome(QuartPos.fromBlock(site.getMinBlockX()) + qx,
                    QuartPos.fromBlock(64), QuartPos.fromBlock(site.getMinBlockZ()) + qz);
                biomes.merge(biome.unwrapKey().map(k -> k.location().toString()).orElse("?"), 1, Integer::sum);
            }
        }
        return "solid=" + solid + " columns=" + columns + "/256"
            + (columns == 0 ? "" : " top=" + lowTop + ".." + highTop)
            + " biomes=" + biomes + " blocks=" + top(blocks, 4);
    }

    private static String top(Map<String, Integer> counts, int n) {
        StringBuilder sb = new StringBuilder("{");
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n)
            .forEach(e -> sb.append(sb.length() > 1 ? ", " : "").append(e.getKey()).append('=').append(e.getValue()));
        return sb.append('}').toString();
    }
}
