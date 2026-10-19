package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.NetherBandContextEvents;
import games.brennan.dungeontrain.mixin.JigsawStructureAccessor;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.OthersideCore;
import games.brennan.dungeontrain.worldgen.OthersideSettings;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Deeper Darker's <b>Ancient Temple</b> in a Deeper Dark core, as the Otherside has it: the mod's own jigsaw
 * pools assembled by vanilla's {@link JigsawPlacement}, through DT's {@code dungeontrain:otherside_temples}
 * structure set (the mod's spacing, its own salt).
 *
 * <p>The one translation is height. The temple rolls its {@code start_height} ({@code above_bottom 18..28})
 * against the Otherside's 0..128 ({@link OthersideSettings#generator}), and that Otherside y is moved onto the stamped slab with
 * {@link OthersideCore#displayY}, exactly as the slab's terrain is — so it stands in the same rock it would in
 * the Otherside. It only sites where the whole footprint is Deeper Dark core and the column's Otherside biome
 * is one the temple allows (the deeplands).</p>
 */
public final class BandOthersideStructures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    /** The Ancient Temple's start pool — the one jigsaw structure this class sites. */
    static final ResourceLocation TEMPLE_START_POOL =
            ResourceLocation.fromNamespaceAndPath(OthersideCore.NAMESPACE, "ancient_temple/basement");

    /** The Otherside's build height, which the temple's {@code above_bottom} heights are relative to. */
    private static final LevelHeightAccessor OTHERSIDE_HEIGHT = LevelHeightAccessor.create(
            OthersideCore.SAMPLE_Y_MIN, OthersideCore.SAMPLE_Y_MAX - OthersideCore.SAMPLE_Y_MIN + 1);

    private BandOthersideStructures() {}

    /** True for Deeper Darker's Ancient Temple. */
    public static boolean isTemple(JigsawStructure jigsaw) {
        try {
            return ((JigsawStructureAccessor) (Object) jigsaw).dungeontrain$startPool()
                    .unwrapKey().map(key -> key.location().equals(TEMPLE_START_POOL)).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True for a structure this class sites — the temple — so the overworld keeps its structure set. */
    public static boolean isBandEligible(Structure structure) {
        return structure instanceof JigsawStructure jigsaw && isTemple(jigsaw);
    }

    /**
     * The temple's generation point in a Deeper Dark core, or empty when this attempt isn't in one (vanilla's
     * own siting then runs, which in the overworld finds no deeplands and places nothing).
     */
    public static Optional<Structure.GenerationStub> site(JigsawStructure structure, Structure.GenerationContext context,
                                                          HolderSet<Biome> biomes) {
        try {
            NetherBandContext ctx = NetherBandContext.current();
            if (ctx == null || !ctx.enabled() || ctx.cycle() == null || ctx.netherCoreBiomes() == null) return Optional.empty();
            if (!ctx.netherCoreBiomes().hasOtherside()) return Optional.empty();
            if (context.chunkGenerator().getBiomeSource() != ctx.overworldBiomeSource()) return Optional.empty();

            JigsawStructureAccessor config = (JigsawStructureAccessor) (Object) structure;
            int reach = config.dungeontrain$maxDistanceFromCenter();
            ChunkPos chunkPos = context.chunkPos();
            int minX = chunkPos.getMinBlockX() - reach;
            int maxX = chunkPos.getMaxBlockX() + reach;
            if (!NetherStructureSiting.isCoreFootprint(ctx.cycle(), minX, maxX)) return Optional.empty();
            if (!NetherBand.isOthersideAt(ctx.cycle(), minX) || !NetherBand.isOthersideAt(ctx.cycle(), maxX)) {
                return Optional.empty();
            }

            // At the start corner — where vanilla's own biome check reads the stub afterwards.
            Holder<Biome> biome = ctx.netherCoreBiomes().biomeAt(chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                    CycleLayout.Style.OTHERSIDE);
            if (!biomes.contains(biome)) return Optional.empty();

            // Rolled against the Otherside's own generator and height: the overworld generator's floor (DT's
            // sits above y 0) would lift every above_bottom anchor by that much.
            OthersideSettings otherside = ctx.otherside();
            if (otherside == null) return Optional.empty();
            int othersideY = config.dungeontrain$startHeight().sample(context.random(),
                    new WorldGenerationContext(otherside.generator(), OTHERSIDE_HEIGHT));
            int bedY = ctx.netherTop() - NetherBandContextEvents.NETHER_TOP_ABOVE_BED;
            BlockPos start = new BlockPos(chunkPos.getMinBlockX(), OthersideCore.displayY(othersideY, bedY),
                    chunkPos.getMinBlockZ());

            return JigsawPlacement.addPieces(
                    context,
                    config.dungeontrain$startPool(),
                    Optional.empty(),
                    config.dungeontrain$maxDepth(),
                    start,
                    config.dungeontrain$useExpansionHack(),
                    Optional.empty(),          // no heightmap projection — the temple sits at its rolled height
                    reach,
                    PoolAliasLookup.create(List.of(), start, context.seed()),
                    DimensionPadding.ZERO,
                    LiquidSettings.APPLY_WATERLOGGING);
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Ancient Temple siting failed; no temple in this chunk", t);
            return Optional.empty();
        }
    }
}
