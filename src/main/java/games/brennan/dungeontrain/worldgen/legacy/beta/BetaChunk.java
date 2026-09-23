package games.brennan.dungeontrain.worldgen.legacy.beta;

/**
 * One generated Beta chunk column: the {@code 16 × 128 × 16} block ids ({@link BetaBlocks}, index
 * {@link BetaTerrain#index}) and the per-column biome ({@code x·16 + z}).
 */
public record BetaChunk(byte[] blocks, BetaBiome[] biomes) {

    public byte get(int x, int y, int z) {
        return blocks[BetaTerrain.index(x, y, z)];
    }

    public BetaBiome biome(int x, int z) {
        return biomes[x * 16 + z];
    }
}
