package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Vanilla's ancient city, assembled from vanilla's own {@code minecraft:ancient_city/city_center} pool, in
 * the Nether band's fall-side deep-dark caverns. Vanilla anchors the city at y −27 in the real deep dark,
 * far under the band; here {@link AncientCitySite} chooses one cave region per Nether pass (pass 0: 50 %,
 * later passes: always) and the anchor sits {@link AncientCitySite#ANCHOR_ABOVE_BED} blocks over the track
 * bed, so the city's floor is just under the rails and the viaduct runs through it mid-height.
 *
 * <p>The structure set offers every chunk (spacing 1); this returns empty for all but the chosen region's
 * centre chunk, a cheap memoised lookup. {@link #findValidGenerationPoint} skips vanilla's biome filter — the
 * region is forced to {@code deep_dark} by the biome mixin, and the site itself is the authoritative gate.
 * Gated by the {@code netherStructures} config like the band's other Nether structures.</p>
 */
public class BandAncientCityStructure extends Structure {

    public static final MapCodec<BandAncientCityStructure> CODEC = simpleCodec(BandAncientCityStructure::new);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);
    private static final ResourceKey<StructureTemplatePool> START_POOL = ResourceKey.create(
            Registries.TEMPLATE_POOL, ResourceLocation.withDefaultNamespace("ancient_city/city_center"));
    private static final ResourceLocation START_JIGSAW = ResourceLocation.withDefaultNamespace("city_anchor");
    private static final int MAX_DEPTH = 7;
    private static final int MAX_DISTANCE_FROM_CENTER = 116;

    public BandAncientCityStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        try {
            if (!DungeonTrainCommonConfig.isNetherStructuresEnabled()) return Optional.empty();
            NetherBandContext ctx = NetherBandContext.current();
            if (ctx == null || !ctx.enabled() || ctx.cycle() == null || ctx.highlandBiomes() == null
                    || ctx.netherCore() == null) {
                return Optional.empty();
            }
            if (context.chunkGenerator().getBiomeSource() != ctx.overworldBiomeSource()) return Optional.empty();

            ChunkPos chunkPos = context.chunkPos();
            long pass = ctx.cycle().netherPassIndex(chunkPos.getMinBlockX());
            long cell = ctx.highlandBiomes().ancientCityCell(ctx, pass);
            if (cell == AncientCitySite.NONE) return Optional.empty();
            int anchorX = AncientCitySite.centreX(cell);
            int anchorZ = AncientCitySite.centreZ();
            if ((anchorX >> 4) != chunkPos.x || (anchorZ >> 4) != chunkPos.z) return Optional.empty();

            int bedY = ctx.netherCore().bedY();
            BlockPos start = new BlockPos(anchorX, bedY + AncientCitySite.ANCHOR_ABOVE_BED, anchorZ);
            Holder<StructureTemplatePool> pool = context.registryAccess()
                    .lookupOrThrow(Registries.TEMPLATE_POOL).getOrThrow(START_POOL);
            return JigsawPlacement.addPieces(
                    context, pool, Optional.of(START_JIGSAW), MAX_DEPTH, start,
                    false,                     // no expansion hack — as vanilla's ancient city
                    Optional.empty(),          // no heightmap projection — anchored inside the mountain
                    MAX_DISTANCE_FROM_CENTER,
                    PoolAliasLookup.create(List.of(), start, context.seed()),
                    DimensionPadding.ZERO,
                    LiquidSettings.APPLY_WATERLOGGING);
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Ancient-city siting failed; skipping this city", t);
            return Optional.empty();
        }
    }

    /** Vanilla's biome filter, skipped — the site is the gate and the region is forced to deep dark. */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext context) {
        return this.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return ModStructureTypes.ANCIENT_CITY.get();
    }
}
