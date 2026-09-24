package games.brennan.dungeontrain.worldgen.legacy.classic;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;

/**
 * The block ids a Classic level is built from, in the old numeric scheme Classic and Beta share (so the
 * terrain ids are {@link BetaBlocks}'s own). {@code LegacyChunkWriter} maps them onto block states.
 */
public final class ClassicBlocks {
    public static final byte AIR = BetaBlocks.AIR;
    public static final byte STONE = BetaBlocks.STONE;
    public static final byte GRASS = BetaBlocks.GRASS;
    public static final byte DIRT = BetaBlocks.DIRT;
    public static final byte BEDROCK = BetaBlocks.BEDROCK;
    public static final byte WATER = BetaBlocks.WATER;
    public static final byte LAVA = BetaBlocks.LAVA;
    public static final byte SAND = BetaBlocks.SAND;
    public static final byte GRAVEL = BetaBlocks.GRAVEL;
    public static final byte GOLD_ORE = 14;
    public static final byte IRON_ORE = 15;
    public static final byte COAL_ORE = 16;
    public static final byte LOG = 17;
    public static final byte LEAVES = 18;
    public static final byte DANDELION = 37;
    public static final byte ROSE = 38;
    public static final byte BROWN_MUSHROOM = 39;
    public static final byte RED_MUSHROOM = 40;

    private ClassicBlocks() {}
}
