package games.brennan.dungeontrain.worldgen.legacy.beta;

/**
 * The handful of block ids the Beta terrain pipeline works in, using Beta 1.7.3's own numbering. The
 * terrain, surface and cave passes run on a flat {@code byte[]} of these (as the original did) and only the
 * final chunk write maps them onto block states — see {@code LegacyChunkWriter}.
 */
public final class BetaBlocks {
    public static final byte AIR = 0;
    public static final byte STONE = 1;
    public static final byte GRASS = 2;
    public static final byte DIRT = 3;
    public static final byte BEDROCK = 7;
    public static final byte WATER = 9;
    public static final byte LAVA = 11;
    public static final byte SAND = 12;
    public static final byte GRAVEL = 13;
    public static final byte SANDSTONE = 24;
    public static final byte ICE = 79;

    private BetaBlocks() {}
}
