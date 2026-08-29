package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.EndIslandGeometry;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.EndCoreBiomes;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * End cities on the disintegration band's End islands — the real thing, sited the way the real End sites
 * them.
 *
 * <p>The pieces are vanilla's: {@link EndCityPieces#startHouseTower} builds the same towers, bridges,
 * ships, loot chests and shulkers as {@code minecraft:end_city}, and the structure set that drives this
 * (see {@code data/dungeontrain/worldgen/structure_set/end_city.json}) carries vanilla's salt. What differs
 * is the <b>siting</b> and the <b>spacing</b>.</p>
 *
 * <p>Siting differs because it has to: the band lives in the overworld, where the chunk generator's surface
 * height describes terrain that the band erodes to void. Vanilla's {@code EndCityStructure} would read that
 * height and drop cities nowhere near an island.</p>
 *
 * <p>Spacing differs as a balance call. The set uses {@code spacing 40 / separation 15} against vanilla's
 * {@code 20 / 11} — a quarter the candidate density — and on top of that a {@code frequency} of
 * {@code 0.2}, which keeps only one in five of those candidate chunks. The band is not the real End: its
 * core is a ~5000-block strip the train drives straight down the middle of, so a player sweeps a long thin
 * corridor of the placement grid rather than wandering a whole dimension, and vanilla's grid made cities
 * read as furniture rather than a find.</p>
 *
 * <p>So {@link #findGenerationPoint} keeps vanilla's rule — random rotation, the 5×5 box offset 7 blocks,
 * take the <em>lowest</em> corner, reject if too low — but reads its heights from
 * {@link EndIslandGeometry}, the same End-density sampling that stamps the islands themselves. A city
 * therefore always stands on island, never over the gap between two.</p>
 *
 * <p>On top of that it applies the band's own gates, all read from the {@link NetherBandContext}
 * server-start snapshot so worldgen workers never touch the server:</p>
 * <ul>
 *   <li>the column must be an End-band <b>core</b> column ({@link WorldGenCycle#isEndCore}) — this is what
 *       keeps cities out of the ordinary overworld;</li>
 *   <li>every sampled column must carry island, with the lowest at or above sea level (the band's
 *       analogue of vanilla's {@code y < 60} floor);</li>
 *   <li>the real End biome of the island field ({@link EndCoreBiomes#islandFieldBiomeAt}) must be
 *       {@code end_highlands} or {@code end_midlands} — vanilla's
 *       {@code #minecraft:has_structure/end_city} restriction, asked of the same patch of outer End the
 *       island itself (and its chorus) came from.</li>
 * </ul>
 *
 * <p>Because that last check is the authoritative one, {@link #findValidGenerationPoint} skips the base
 * class's biome filter: it would re-ask the <em>overworld</em> biome source, whose End-biome answer
 * depends on the Nether band's biome-forcing mixin being active. The gate above is exact either way.</p>
 */
public class BandEndCityStructure extends Structure {

    public static final MapCodec<BandEndCityStructure> CODEC = simpleCodec(BandEndCityStructure::new);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    public BandEndCityStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        try {
            if (!DungeonTrainCommonConfig.isDisintegrationEndCitiesEnabled()) return Optional.empty();
            NetherBandContext ctx = NetherBandContext.current();
            if (ctx == null || ctx.cycle() == null || ctx.endIslands() == null) return Optional.empty();
            // Overworld-only: the band (and its island geometry) exists in exactly one dimension.
            if (context.chunkGenerator().getBiomeSource() != ctx.overworldBiomeSource()) return Optional.empty();

            ChunkPos chunkPos = context.chunkPos();
            Rotation rotation = Rotation.getRandom(context.random());
            int anchorX = chunkPos.getBlockX(EndCitySiting.PROBE_ANCHOR);
            int anchorZ = chunkPos.getBlockZ(EndCitySiting.PROBE_ANCHOR);
            int span = EndCitySiting.PROBE_SPAN;
            int spanX = (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_180) ? -span : span;
            int spanZ = (rotation == Rotation.CLOCKWISE_180 || rotation == Rotation.COUNTERCLOCKWISE_90) ? -span : span;

            WorldGenCycle cycle = ctx.cycle();
            // Bottom of the island geometry is the world's floor, not the level's — a city has to
            // site itself against the same range DisintegrationFeature stamped its islands over.
            EndIslandGeometry geometry = ctx.endIslands().open(
                    WorldFloor.bedrockY(context.heightAccessor(), context.chunkGenerator()),
                    context.heightAccessor().getMaxBuildHeight() - 1);
            int baseY = EndCitySiting.siteY(cycle, geometry, ctx.seaLevel(), anchorX, anchorZ, spanX, spanZ);
            if (baseY == Integer.MIN_VALUE) return Optional.empty();

            int endY = EndIslandGeometry.END_ISLAND_CENTER_Y + (baseY - ctx.endIslands().bedY());
            if (!isCityBiome(ctx.endCoreBiomes(), anchorX, endY, anchorZ)) return Optional.empty();

            BlockPos start = new BlockPos(anchorX, baseY, anchorZ);
            return Optional.of(new GenerationStub(start, builder -> generatePieces(builder, start, rotation, context)));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] End-city siting failed; skipping this city", t);
            return Optional.empty();
        }
    }

    /**
     * The band's End-biome gate — vanilla restricts end cities to {@code end_highlands} /
     * {@code end_midlands}, and {@link EndCoreBiomes#islandFieldBiomeAt} answers that against the same
     * patch of outer End the island was carved from, so a city only lands where the real End would have
     * put one on that very island.
     */
    private static boolean isCityBiome(EndCoreBiomes endBiomes, int worldX, int endY, int worldZ) {
        if (endBiomes == null) return false;
        Holder<Biome> biome = endBiomes.islandFieldBiomeAt(worldX, endY, worldZ);
        return biome.is(Biomes.END_HIGHLANDS) || biome.is(Biomes.END_MIDLANDS);
    }

    /**
     * Vanilla's biome filter, deliberately skipped — {@link #findGenerationPoint} already applied the End
     * biome rule against the real End's own biome source, which is exact regardless of what the overworld
     * biome source reports for the column.
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext context) {
        return this.findGenerationPoint(context);
    }

    /** Vanilla's own piece generation, verbatim. */
    private void generatePieces(StructurePiecesBuilder builder, BlockPos startPos, Rotation rotation, GenerationContext context) {
        List<StructurePiece> pieces = new ArrayList<>();
        EndCityPieces.startHouseTower(context.structureTemplateManager(), startPos, rotation, pieces, context.random());
        pieces.forEach(builder::addPiece);
    }

    @Override
    public StructureType<?> type() {
        return ModStructureTypes.END_CITY.get();
    }
}
