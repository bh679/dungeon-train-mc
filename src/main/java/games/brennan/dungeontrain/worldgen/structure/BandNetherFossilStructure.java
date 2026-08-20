package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilPieces;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Nether fossils in the Nether band's core — vanilla's bone-block skeletons, laid on the band's own soul
 * sand the way the real Nether lays them on its own.
 *
 * <p>Vanilla rolls a height, then walks <em>down</em> the chunk generator's noise column until it finds
 * open air resting on solid ground, and buries the fossil there. In the band that column would describe the
 * overworld mountain the core replaces, so the walk reads {@link NetherCoreGeometry} instead — the same
 * sampling of the real Nether's density that stamped the netherrack and soul sand this fossil lies in. The
 * roll itself, the pieces and the biome restriction are all vanilla's, which is why fossils appear only
 * where the core rolls a soul sand valley.</p>
 */
public class BandNetherFossilStructure extends Structure {

    public static final MapCodec<BandNetherFossilStructure> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    settingsCodec(instance),
                    HeightProvider.CODEC.fieldOf("height").forGetter(s -> s.height)
            ).apply(instance, BandNetherFossilStructure::new));

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    /** A fossil is a handful of blocks; it barely leaves its own chunk. */
    private static final int FOOTPRINT_RADIUS = 16;

    public final HeightProvider height;

    public BandNetherFossilStructure(StructureSettings settings, HeightProvider height) {
        super(settings);
        this.height = height;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        try {
            BandNetherStructures.Site site = BandNetherStructures.open(context, FOOTPRINT_RADIUS);
            if (site == null) return Optional.empty();

            // Vanilla's draws, in vanilla's order.
            WorldgenRandom random = context.random();
            int worldX = context.chunkPos().getMinBlockX() + random.nextInt(16);
            int worldZ = context.chunkPos().getMinBlockZ() + random.nextInt(16);
            int netherY = this.height.sample(random,
                    new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));

            if (!site.biomeAllows(this.biomes(), worldX, worldZ)) return Optional.empty();

            // Vanilla's downward walk for open air resting on solid ground, run against the core terrain.
            int groundY = site.column(worldX, worldZ).floorBelow(site.bandYInCore(netherY));
            if (groundY == Integer.MIN_VALUE) return Optional.empty();

            BlockPos start = new BlockPos(worldX, groundY, worldZ);
            return Optional.of(new GenerationStub(start, builder ->
                    NetherFossilPieces.addPieces(context.structureTemplateManager(), builder, random, start)));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Nether-fossil siting failed; skipping this fossil", t);
            return Optional.empty();
        }
    }

    /**
     * Vanilla's biome filter, deliberately skipped — {@link #findGenerationPoint} already applied the
     * soul-sand-valley restriction against the real Nether's own biome source, which is exact regardless
     * of what the overworld biome source reports for the column.
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext context) {
        return this.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return ModStructureTypes.NETHER_FOSSIL.get();
    }
}
