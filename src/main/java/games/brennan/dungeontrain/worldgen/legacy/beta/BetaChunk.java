package games.brennan.dungeontrain.worldgen.legacy.beta;

/**
 * One generated Beta-family chunk column: the {@code 16 × height × 16} block ids ({@link BetaBlocks},
 * index {@link BetaTerrain#index(int, int, int, int)} — Beta's own layout at {@code height == 128}) and
 * the per-column biome ({@code x·16 + z}).
 */
public record BetaChunk(byte[] blocks, BetaBiome[] biomes, int height) {

    /** A Beta-height (128) column. */
    public BetaChunk(byte[] blocks, BetaBiome[] biomes) {
        this(blocks, biomes, BetaTerrain.HEIGHT);
    }

    public byte get(int x, int y, int z) {
        return blocks[BetaTerrain.index(x, y, z, height)];
    }

    public BetaBiome biome(int x, int z) {
        return biomes[x * 16 + z];
    }
}
