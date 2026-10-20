package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.OthersideCore;
import games.brennan.dungeontrain.worldgen.OthersideSettings;
import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import org.slf4j.Logger;

/**
 * The Otherside's carvers ({@code deeperdarker:cave}, {@code deeperdarker:low_cave}) cut into a Deeper Dark
 * core chunk in place — what {@code NoiseBasedChunkGenerator.applyCarvers} does to an Otherside chunk, run
 * over the overworld chunk's stamped slab instead.
 *
 * <p>The one translation is height: the carvers' {@code above_bottom} / {@code below_top} anchors (their y
 * range and lava level) resolve against the {@link CarvingContext}'s height, so it is given the slab —
 * {@code LevelHeightAccessor.create(slabBottom, 128)} with the Otherside's own generator, whose
 * {@code getMinY() == 0} and {@code getGenDepth() == 128} keep {@code WorldGenerationContext} inside it. Carver
 * writes are absolute-Y into the chunk and clamp to that range, so nothing outside the slab is touched. The
 * carve seeds are vanilla's ({@code setLargeFeatureSeed(worldSeed + carverIndex, cx, cz)} over the 17×17
 * neighbourhood, each neighbour's carvers from its Otherside biome), so the caves are the Otherside's own.</p>
 *
 * <p>A fresh {@link CarvingMask} rather than the chunk's: the overworld's carvers already marked their cells
 * in it, and a cell they carved inside the slab would then be skipped. Aquifers are off in the Otherside, so
 * the disabled aquifer with the Otherside's fluid (sculk grime below its sea level, translated onto the
 * slab) stands in for the noise chunk's. The carving context's noise chunk is {@code null}: it feeds only
 * {@code topMaterial}, which vanilla calls for a carved grass/mycelium-over-dirt floor — never in the
 * Otherside.</p>
 */
public final class OthersideCarve {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN CARVE_ERRORS = new LogFirstN(5);
    /** Vanilla's carver neighbourhood radius, in chunks. */
    private static final int RADIUS = 8;

    private OthersideCarve() {}

    /**
     * Carve the Otherside's caves into {@code chunk}'s slab. {@code biomes} answers the Otherside biome at a
     * column ({@code NetherCoreBiomes}, which samples the Otherside's climate anywhere). Never throws.
     *
     * @return true if the carvers ran (not whether they changed anything)
     */
    public static boolean carve(ChunkAccess chunk, int bedY, OthersideSettings otherside, NetherCoreBiomes biomes,
                                NetherCoreBiomes.SampleMemo memo, RegistryAccess registries, long worldSeed) {
        try {
            int slabBottom = Math.max(chunk.getMinBuildHeight(), OthersideCore.slabBottom(bedY));
            int slabHeight = OthersideCore.SAMPLE_Y_MAX - OthersideCore.SAMPLE_Y_MIN + 1;
            if (slabBottom + slabHeight > chunk.getMaxBuildHeight()) return false;   // the slab must fit the world
            LevelHeightAccessor slab = LevelHeightAccessor.create(slabBottom, slabHeight);
            CarvingContext context = new CarvingContext(otherside.generator(), registries, slab, null,
                    otherside.random(), otherside.settings().surfaceRule());
            // The Otherside's sea, on the slab: its sea level is relative to its own y 0.
            Aquifer.FluidStatus grime = new Aquifer.FluidStatus(OthersideCore.displayY(otherside.seaLevel(), bedY),
                    otherside.defaultFluid());
            Aquifer aquifer = Aquifer.createDisabled((x, y, z) -> grime);
            BiomeManager biomeManager = new BiomeManager(
                    (qx, qy, qz) -> biomes.biomeAt(QuartPos.toBlock(qx), QuartPos.toBlock(qz), CycleLayout.Style.OTHERSIDE, memo),
                    BiomeManager.obfuscateSeed(worldSeed));
            CarvingMask mask = new CarvingMask(chunk.getHeight(), chunk.getMinBuildHeight());
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
            ChunkPos pos = chunk.getPos();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    ChunkPos neighbour = new ChunkPos(pos.x + dx, pos.z + dz);
                    // Vanilla reads the carver biome at the neighbour's min corner, quart y 0 (the Otherside's
                    // climate is flat in y, so the label's own row gives the same biome).
                    Holder<Biome> biome = biomes.biomeAt(neighbour.getMinBlockX(), neighbour.getMinBlockZ(),
                            CycleLayout.Style.OTHERSIDE, memo);
                    int index = 0;
                    for (Holder<ConfiguredWorldCarver<?>> holder : biome.value().getGenerationSettings().getCarvers(GenerationStep.Carving.AIR)) {
                        ConfiguredWorldCarver<?> carver = holder.value();
                        random.setLargeFeatureSeed(worldSeed + index, neighbour.x, neighbour.z);
                        if (carver.isStartChunk(random)) {
                            carver.carve(context, chunk, biomeManager::getBiome, random, aquifer, neighbour, mask);
                        }
                        index++;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            CARVE_ERRORS.error(LOGGER, "[DungeonTrain] Otherside carvers failed at " + chunk.getPos() + "; that chunk keeps uncarved Deeper Dark terrain", t);
            return false;
        }
    }
}
