package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
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
 * Bastion remnants in the Nether band's core — vanilla's jigsaw, assembled from vanilla's
 * {@code minecraft:bastion/starts} pool, so the band gets the real housing units, hoglin stables, treasure
 * rooms and bridges, with their piglin brutes, Pigstep disc and netherite upgrade template.
 *
 * <p>This has to be its own {@link Structure} rather than a configured {@code minecraft:jigsaw}: that class
 * is {@code final}, and its {@code findGenerationPoint} reads {@code start_height} as an absolute world Y.
 * The band needs the same number read as a <b>Nether-space</b> Y and translated onto track level, exactly
 * as the core terrain translates the Nether it samples. So the datapack entry is vanilla's bastion entry
 * verbatim — same pool, same size, same uniform 33–100 start height, same 80-block reach — and only the
 * reading of that height differs.</p>
 *
 * <p>Everything after the start position is {@link JigsawPlacement#addPieces}, vanilla's own assembly. Like
 * vanilla's bastion, no heightmap projection is applied: bastions sit at the height they roll, embedded in
 * whatever netherrack is there and open to the air where there is none.</p>
 */
public class BandBastionRemnantStructure extends Structure {

    /** Vanilla {@code JigsawStructure} limits, mirrored so the datapack entry validates the same way. */
    private static final int MAX_DEPTH = 20;
    private static final int MAX_DISTANCE_FROM_CENTER = 128;
    private static final int DEFAULT_MAX_DISTANCE_FROM_CENTER = 80;

    public static final MapCodec<BandBastionRemnantStructure> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    settingsCodec(instance),
                    StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
                    Codec.intRange(0, MAX_DEPTH).fieldOf("size").forGetter(s -> s.maxDepth),
                    HeightProvider.CODEC.fieldOf("start_height").forGetter(s -> s.startHeight),
                    Codec.BOOL.optionalFieldOf("use_expansion_hack", false).forGetter(s -> s.useExpansionHack),
                    Codec.intRange(1, MAX_DISTANCE_FROM_CENTER).optionalFieldOf("max_distance_from_center",
                            DEFAULT_MAX_DISTANCE_FROM_CENTER).forGetter(s -> s.maxDistanceFromCenter)
            ).apply(instance, BandBastionRemnantStructure::new));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    private final Holder<StructureTemplatePool> startPool;
    private final int maxDepth;
    private final HeightProvider startHeight;
    private final boolean useExpansionHack;
    private final int maxDistanceFromCenter;

    public BandBastionRemnantStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool,
                                       int maxDepth, HeightProvider startHeight, boolean useExpansionHack,
                                       int maxDistanceFromCenter) {
        super(settings);
        this.startPool = startPool;
        this.maxDepth = maxDepth;
        this.startHeight = startHeight;
        this.useExpansionHack = useExpansionHack;
        this.maxDistanceFromCenter = maxDistanceFromCenter;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        try {
            BandNetherStructures.Site site = BandNetherStructures.open(context, this.maxDistanceFromCenter);
            if (site == null) return Optional.empty();

            ChunkPos chunkPos = context.chunkPos();
            if (!site.biomeAllows(this.biomes(), chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())) {
                return Optional.empty();
            }

            // Vanilla's roll, read as a Nether-space Y and translated onto the band — the same draw, from
            // the same random, in the same order, so a seed produces the same bastion at the same height
            // relative to the Nether it was sampled from.
            int netherY = this.startHeight.sample(context.random(),
                    new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));
            BlockPos start = new BlockPos(chunkPos.getMinBlockX(), site.bandYInCore(netherY),
                    chunkPos.getMinBlockZ());

            return JigsawPlacement.addPieces(
                    context,
                    this.startPool,
                    Optional.empty(),
                    this.maxDepth,
                    start,
                    this.useExpansionHack,
                    Optional.empty(),          // no heightmap projection — as vanilla's bastion
                    this.maxDistanceFromCenter,
                    PoolAliasLookup.create(List.of(), start, context.seed()),
                    DimensionPadding.ZERO,
                    LiquidSettings.APPLY_WATERLOGGING);
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Bastion siting failed; skipping this bastion", t);
            return Optional.empty();
        }
    }

    /**
     * Vanilla's biome filter, deliberately skipped — {@link #findGenerationPoint} already applied the
     * Nether biome rule against the real Nether's own biome source. That matters especially here: a
     * bastion's rolled height regularly lands below sea level, where the band's biome-forcing mixin yields
     * to the original overworld biome and the base filter would reject a perfectly good site.
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext context) {
        return this.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return ModStructureTypes.BASTION_REMNANT.get();
    }
}
