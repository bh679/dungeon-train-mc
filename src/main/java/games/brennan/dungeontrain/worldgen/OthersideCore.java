package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The core of a Deeper Dark pass ({@code nether:otherside} in the cycle order): Deeper Darker's <b>Otherside</b>
 * generated into the band whole — bedrock floor, bedrock roof and everything between — from its own rules
 * ({@link OthersideSettings}), in place in the overworld chunk, never by generating an Otherside chunk.
 *
 * <p>The Otherside is a 128-tall roofed cave world (y 0..127; bedrock 5 deep at both ends, deepslate inside
 * them, sculk stone between, a sculk-grime sea at y 17). Its y {@link #CENTER_Y} sits on the track bed, so the
 * slab spans {@code bedY − 40 .. bedY + 87}: the roof is ~75 blocks over the tunnel and the floor ~40 under it,
 * so the track crosses the slab through its open sides and never meets bedrock.</p>
 *
 * <p>Each display chunk generates the Otherside at the same block coordinates — noise ({@link OthersideGeometry}),
 * surface rule ({@code feature.OthersideSurfaceSkin}) and carvers ({@code feature.OthersideCarve}), run by
 * {@code feature.NetherCoreStamp} on the calling worldgen worker — so neighbouring chunks join up exactly as
 * they do in the Otherside itself. Decoration is the band's core decoration pass, which runs the Otherside
 * biomes' own features over the stamped slab, like the Nether core's.</p>
 *
 * <p>This class holds the slab's constants and y mapping. Deeper Darker is a hard dependency, but every caller
 * still treats missing rules as "no Otherside": the pass then falls back to a vanilla Nether core rather than
 * leaving a hole.</p>
 */
public final class OthersideCore {

    /** Deeper Darker's mod id / registry namespace. */
    public static final String NAMESPACE = "deeperdarker";
    /** The Otherside dimension (the key only — DT reads nothing from the level). */
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(NAMESPACE, "otherside"));

    /** Otherside y that sits on the track bed. */
    public static final int CENTER_Y = 40;
    /** Lowest Otherside y generated (its bedrock floor). */
    public static final int SAMPLE_Y_MIN = 0;
    /** Highest Otherside y generated (its bedrock roof). */
    public static final int SAMPLE_Y_MAX = 127;

    /** {@code deeperdarker:sculk_stone} — the Otherside's default block; deepslate when Deeper Darker is absent. */
    private static volatile BlockState sculkStone;

    private OthersideCore() {}

    /** Deeper Darker's sculk stone (the crossfade skin into the Deeper Dark), or deepslate without the mod. */
    public static BlockState sculkStone() {
        BlockState s = sculkStone;
        if (s == null) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "sculk_stone"));
            s = block == Blocks.AIR ? Blocks.DEEPSLATE.defaultBlockState() : block.defaultBlockState();
            sculkStone = s;
        }
        return s;
    }

    /** True for one of Deeper Darker's Otherside biomes — the label a Deeper Dark core carries. */
    public static boolean isOthersideBiome(Holder<Biome> biome) {
        return biome != null && biome.unwrapKey()
                .map(k -> NAMESPACE.equals(k.location().getNamespace()))
                .orElse(false);
    }

    /** True for a block Deeper Darker registers — the Otherside's rock, decoration and ores. */
    public static boolean isOthersideBlock(BlockState state) {
        return NAMESPACE.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    /** World y of Otherside y {@code othersideY} on a track bed at {@code bedY}. */
    public static int displayY(int othersideY, int bedY) {
        return bedY + (othersideY - CENTER_Y);
    }

    /** Otherside y of world y {@code worldY} on a track bed at {@code bedY}. */
    public static int othersideY(int worldY, int bedY) {
        return worldY - bedY + CENTER_Y;
    }

    /** Lowest world y of the stamped slab (its bedrock floor) on a bed at {@code bedY}. */
    public static int slabBottom(int bedY) {
        return displayY(SAMPLE_Y_MIN, bedY);
    }

    /** Highest world y of the stamped slab (its bedrock roof) on a bed at {@code bedY}. */
    public static int slabTop(int bedY) {
        return displayY(SAMPLE_Y_MAX, bedY);
    }
}
