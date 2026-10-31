package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilPieces;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Where a Nether fossil lies in the band — vanilla's bone-block skeletons, laid on the band's own soul sand
 * the way the real Nether lays them on its own.
 *
 * <p>Vanilla rolls a height, then walks <em>down</em> the chunk generator's noise column until it finds open
 * air resting on solid ground, and buries the fossil there. In the band that column would describe the
 * overworld mountain the core replaces, so the walk reads {@link NetherCoreGeometry} instead — the same
 * sampling of the real Nether's density that stamped the netherrack and soul sand this fossil lies in. The
 * roll, the pieces and the biome restriction are all vanilla's, which is why fossils appear only where the
 * core rolls a soul sand valley.</p>
 */
public final class BandFossilSiting {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    /** A fossil is a handful of blocks; it barely leaves its own chunk. */
    private static final int FOOTPRINT_RADIUS = 16;
    /**
     * Rows a fossil stands above the floor it is sited on (the tallest of vanilla's skeletons is 7). The walk
     * starts this far under the slab's top, so no skeleton sticks out of the band's roof into the sky.
     */
    private static final int FOSSIL_HEADROOM = 8;

    private BandFossilSiting() {}

    /**
     * The band's answer for a fossil: a generation point, {@link Optional#empty()} for "no fossil here", or
     * {@code null} to let vanilla's own siting run — which is what happens everywhere outside the band core,
     * including the real Nether. Inside the core a decline is final: vanilla's walk would read the overworld
     * noise column the core replaces and lay the skeleton on the slab's roof.
     */
    public static Optional<Structure.GenerationStub> site(Structure.GenerationContext context,
                                                          HeightProvider height, HolderSet<Biome> biomes) {
        try {
            BandNetherStructures.Site band = BandNetherStructures.open(context, FOOTPRINT_RADIUS);
            if (band == null) return null;

            // Vanilla's draws, in vanilla's order.
            WorldgenRandom random = context.random();
            int worldX = context.chunkPos().getMinBlockX() + random.nextInt(16);
            int worldZ = context.chunkPos().getMinBlockZ() + random.nextInt(16);
            int netherY = height.sample(random,
                    new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));

            if (!band.biomeAllows(biomes, worldX, worldZ)) return Optional.empty();

            // Vanilla's downward walk for open air resting on solid ground, run against the core terrain.
            int startY = Math.min(band.bandYInCore(netherY),
                    NetherStructureSiting.maxStructureY(band.bedY()) - FOSSIL_HEADROOM);
            int groundY = band.column(worldX, worldZ).floorBelow(startY);
            if (groundY == Integer.MIN_VALUE) return Optional.empty();

            BlockPos start = new BlockPos(worldX, groundY, worldZ);
            return Optional.of(new Structure.GenerationStub(start, builder ->
                    NetherFossilPieces.addPieces(context.structureTemplateManager(), builder, random, start)));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Nether-fossil siting failed; leaving it to vanilla", t);
            return null;
        }
    }
}
