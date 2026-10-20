package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.worldgen.OthersideCore;
import games.brennan.dungeontrain.worldgen.OthersideSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import java.util.Objects;

/**
 * Deeper Darker's Otherside surface rule ({@code data/deeperdarker/worldgen/noise_settings/otherside.json}
 * {@code surface_rule}), applied column by column to the Deeper Dark core's noise fill — what vanilla's
 * {@code SurfaceSystem.buildSurface} does to an Otherside chunk, without the chunk.
 *
 * <p>The rule, in order, over every sculk-stone block of a column walked top-down (the rule only ever
 * replaces the default block; the stone-depth counter counts every non-air, non-fluid block and resets at
 * air, not at fluid — vanilla's loop exactly):</p>
 * <ol>
 *   <li>{@code vertical_gradient bedrock_floor} 0..5 → bedrock</li>
 *   <li>{@code not vertical_gradient bedrock_roof} top-5..top → bedrock</li>
 *   <li>echoing_forest ∧ {@code stone_depth floor add_surface_depth} → echo soil</li>
 *   <li>{@code stone_depth floor} → blooming sculk stone (blooming_caverns) / gloomy sculk (overcast_columns) / sculk</li>
 *   <li>{@code vertical_gradient deepslate_floor} 0..12 → deepslate</li>
 *   <li>{@code not vertical_gradient deepslate_roof} top-12..top → deepslate</li>
 *   <li>overcast_columns → gloomslate</li>
 * </ol>
 *
 * <p>Every random in it is positional — {@code RandomState.getOrCreateRandomFactory(name).at(x, y, z)} with
 * {@code y} the Otherside y, and vanilla's surface-depth noise + jitter per column — so the result is a pure
 * function of the world seed and the column, byte-identical to the Otherside's own surface for a column of
 * the same biome. The biome is the column's label ({@code NetherCoreBiomes}), not a fuzzed lookup, so a
 * block or two at an Otherside biome edge may differ from the dimension; the band's decoration keys off the
 * same label, so terrain and features agree. {@link OthersideSurfaceRuleShapeTest} pins the rule's shape
 * against the jar DT builds with, so a Deeper Darker update that changes it fails CI rather than drifting.</p>
 */
public final class OthersideSurfaceSkin {

    /** The random names the rule's gradients use (vanilla's, shared with every dimension). */
    static final ResourceLocation BEDROCK_FLOOR = ResourceLocation.withDefaultNamespace("bedrock_floor");
    static final ResourceLocation BEDROCK_ROOF = ResourceLocation.withDefaultNamespace("bedrock_roof");
    static final ResourceLocation DEEPSLATE_FLOOR = ResourceLocation.withDefaultNamespace("deepslate_floor");
    static final ResourceLocation DEEPSLATE_ROOF = ResourceLocation.withDefaultNamespace("deepslate_roof");

    /** Gradient anchors, in Otherside y ({@code above_bottom} / {@code below_top} against y 0..128). */
    static final int BEDROCK_FLOOR_TRUE_AT_AND_BELOW = OthersideCore.SAMPLE_Y_MIN;           // above_bottom 0
    static final int BEDROCK_FLOOR_FALSE_AT_AND_ABOVE = OthersideCore.SAMPLE_Y_MIN + 5;      // above_bottom 5
    static final int BEDROCK_ROOF_TRUE_AT_AND_BELOW = OthersideCore.SAMPLE_Y_MAX - 5;        // below_top 5
    static final int BEDROCK_ROOF_FALSE_AT_AND_ABOVE = OthersideCore.SAMPLE_Y_MAX;           // below_top 0
    static final int DEEPSLATE_FLOOR_TRUE_AT_AND_BELOW = OthersideCore.SAMPLE_Y_MIN;
    static final int DEEPSLATE_FLOOR_FALSE_AT_AND_ABOVE = OthersideCore.SAMPLE_Y_MIN + 12;
    static final int DEEPSLATE_ROOF_TRUE_AT_AND_BELOW = OthersideCore.SAMPLE_Y_MAX - 12;
    static final int DEEPSLATE_ROOF_FALSE_AT_AND_ABOVE = OthersideCore.SAMPLE_Y_MAX;

    static final ResourceKey<Biome> ECHOING_FOREST = biome("echoing_forest");
    static final ResourceKey<Biome> BLOOMING_CAVERNS = biome("blooming_caverns");
    static final ResourceKey<Biome> OVERCAST_COLUMNS = biome("overcast_columns");

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState DEEPSLATE_Y = Blocks.DEEPSLATE.defaultBlockState()
            .setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.Y);
    private static final BlockState SCULK = Blocks.SCULK.defaultBlockState();

    /** The positional randoms behind a gradient — a seam so the rule logic is unit-testable without a world. */
    @FunctionalInterface
    public interface Gradient {
        /** {@code nextFloat()} of the gradient's random at (x, Otherside y, z). */
        float nextFloat(int worldX, int othersideY, int worldZ);
    }

    /** The per-world inputs: four gradient randoms and the surface depth per column. */
    public record Randoms(Gradient bedrockFloor, Gradient bedrockRoof, Gradient deepslateFloor,
                          Gradient deepslateRoof, SurfaceDepth surfaceDepth) {

        /** The Otherside's randoms under {@code settings}' random state. */
        public static Randoms of(OthersideSettings settings) {
            return new Randoms(
                    gradient(settings.random().getOrCreateRandomFactory(BEDROCK_FLOOR)),
                    gradient(settings.random().getOrCreateRandomFactory(BEDROCK_ROOF)),
                    gradient(settings.random().getOrCreateRandomFactory(DEEPSLATE_FLOOR)),
                    gradient(settings.random().getOrCreateRandomFactory(DEEPSLATE_ROOF)),
                    settings::surfaceDepth);
        }

        private static Gradient gradient(PositionalRandomFactory factory) {
            return (x, y, z) -> factory.at(x, y, z).nextFloat();
        }
    }

    /** Vanilla's {@code SurfaceSystem.getSurfaceDepth} for a column. */
    @FunctionalInterface
    public interface SurfaceDepth {
        int at(int worldX, int worldZ);
    }

    /** The Otherside's own blocks, resolved once (deepslate / sculk stand-ins keep the skin running without them). */
    public record Blocks4(BlockState sculkStone, BlockState echoSoil, BlockState bloomingSculkStone,
                          BlockState gloomySculk, BlockState gloomslate) {

        public static Blocks4 resolve() {
            return new Blocks4(OthersideCore.sculkStone(), block("echo_soil", Blocks.SCULK),
                    block("blooming_sculk_stone", Blocks.SCULK), block("gloomy_sculk", Blocks.SCULK),
                    block("gloomslate", Blocks.DEEPSLATE));
        }

        private static BlockState block(String path, Block fallback) {
            Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(OthersideCore.NAMESPACE, path));
            return (b == Blocks.AIR ? fallback : b).defaultBlockState();
        }
    }

    private final Randoms randoms;
    private final Blocks4 blocks;

    public OthersideSurfaceSkin(Randoms randoms, Blocks4 blocks) {
        this.randoms = Objects.requireNonNull(randoms);
        this.blocks = Objects.requireNonNull(blocks);
    }

    /** How a column is read and written — the stamp's {@code ColumnWriter}, or an array in a test. */
    public interface Column {
        /** The block at Otherside y. */
        BlockState get(int othersideY);

        void set(int othersideY, BlockState state);
    }

    /**
     * Dress one column of the noise fill: walk Otherside y {@code top} down to {@code bottom} and apply the
     * rule to every sculk-stone block, as vanilla would. {@code biome} is the column's Otherside biome key
     * (any other key dresses like the deeplands). Returns true if anything changed.
     */
    public boolean dress(Column column, int worldX, int worldZ, int top, int bottom, ResourceKey<Biome> biome) {
        int surfaceDepth = -1;              // resolved on first need; the noise is the costly part
        int stoneDepthAbove = 0;            // vanilla's i2: consecutive non-air, non-fluid blocks from above
        boolean changed = false;
        for (int y = top; y >= bottom; y--) {
            BlockState state = column.get(y);
            if (state.isAir()) {
                stoneDepthAbove = 0;
                continue;
            }
            if (!state.getFluidState().isEmpty()) continue;   // fluid: the depth counter neither resets nor grows
            stoneDepthAbove++;
            if (state != blocks.sculkStone()) continue;        // the rule only replaces the default block
            BlockState out;
            if (gradient(randoms.bedrockFloor(), worldX, y, worldZ, BEDROCK_FLOOR_TRUE_AT_AND_BELOW, BEDROCK_FLOOR_FALSE_AT_AND_ABOVE)) {
                out = BEDROCK;
            } else if (!gradient(randoms.bedrockRoof(), worldX, y, worldZ, BEDROCK_ROOF_TRUE_AT_AND_BELOW, BEDROCK_ROOF_FALSE_AT_AND_ABOVE)) {
                out = BEDROCK;
            } else if (biome == ECHOING_FOREST && stoneDepthAbove <= 1 + (surfaceDepth < 0 ? (surfaceDepth = randoms.surfaceDepth().at(worldX, worldZ)) : surfaceDepth)) {
                out = blocks.echoSoil();
            } else if (stoneDepthAbove <= 1) {
                out = biome == BLOOMING_CAVERNS ? blocks.bloomingSculkStone()
                        : biome == OVERCAST_COLUMNS ? blocks.gloomySculk() : SCULK;
            } else if (gradient(randoms.deepslateFloor(), worldX, y, worldZ, DEEPSLATE_FLOOR_TRUE_AT_AND_BELOW, DEEPSLATE_FLOOR_FALSE_AT_AND_ABOVE)) {
                out = DEEPSLATE_Y;
            } else if (!gradient(randoms.deepslateRoof(), worldX, y, worldZ, DEEPSLATE_ROOF_TRUE_AT_AND_BELOW, DEEPSLATE_ROOF_FALSE_AT_AND_ABOVE)) {
                out = DEEPSLATE_Y;
            } else if (biome == OVERCAST_COLUMNS) {
                out = blocks.gloomslate();
            } else {
                continue;
            }
            if (out != state) {
                column.set(y, out);
                changed = true;
            }
        }
        return changed;
    }

    /** Vanilla's {@code vertical_gradient}: true at and below, false at and above, a linear coin toss between. */
    static boolean gradient(Gradient random, int worldX, int othersideY, int worldZ, int trueAtAndBelow, int falseAtAndAbove) {
        if (othersideY <= trueAtAndBelow) return true;
        if (othersideY >= falseAtAndAbove) return false;
        double d0 = Mth.map(othersideY, trueAtAndBelow, falseAtAndAbove, 1.0, 0.0);
        return random.nextFloat(worldX, othersideY, worldZ) < d0;
    }

    private static ResourceKey<Biome> biome(String path) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath(OthersideCore.NAMESPACE, path));
    }
}
