package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Where a Nether ruined portal stands in the band — vanilla's nether-variant portals, blackstone and all,
 * anchored in the band's own netherrack.
 *
 * <p>Vanilla's own {@code in_nether} rule already ignores terrain height when it picks a Y: it rolls one
 * from a fixed Nether-space range (27-100, or 32-100 when the portal gets an air pocket) and then walks
 * <em>down</em> until three of the footprint's four corners are solid. Only that walk needs replacing --
 * vanilla reads it from the chunk generator's noise columns, which in the band describe the overworld
 * mountain the core replaces, so it reads {@link NetherCoreGeometry} instead. The roll, the template
 * choice, the rotation, the mirror and the piece are vanilla's.</p>
 */
public final class BandRuinedPortalSiting {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    private static final String[] PORTALS = {
        "ruined_portal/portal_1", "ruined_portal/portal_2", "ruined_portal/portal_3", "ruined_portal/portal_4",
        "ruined_portal/portal_5", "ruined_portal/portal_6", "ruined_portal/portal_7", "ruined_portal/portal_8",
        "ruined_portal/portal_9", "ruined_portal/portal_10"
    };
    private static final String[] GIANT_PORTALS = {
        "ruined_portal/giant_portal_1", "ruined_portal/giant_portal_2", "ruined_portal/giant_portal_3"
    };
    private static final float PROBABILITY_OF_GIANT_PORTAL = 0.05F;

    /** Vanilla's Nether-space roll ranges for {@code in_nether}. */
    private static final int AIR_POCKET_MIN_NETHER_Y = 32;
    private static final int LOW_MIN_NETHER_Y = 27;
    private static final int LOW_MAX_NETHER_Y = 29;
    private static final int OPEN_MIN_NETHER_Y = 29;
    private static final int MAX_NETHER_Y = 100;

    /** Corners of the footprint that must be solid for the portal to rest there -- vanilla's rule. */
    private static final int REQUIRED_SOLID_CORNERS = 3;

    /** A portal is a single template; it stays close to its own chunk. */
    private static final int FOOTPRINT_RADIUS = 32;

    private BandRuinedPortalSiting() {}

    /**
     * The band's generation point for a Nether ruined portal, or {@link Optional#empty()} to let vanilla's
     * own siting run -- which is what happens everywhere outside the band core, including the real Nether.
     */
    public static Optional<Structure.GenerationStub> site(Structure.GenerationContext context,
                                                          List<RuinedPortalStructure.Setup> setups,
                                                          HolderSet<Biome> biomes) {
        try {
            BandNetherStructures.Site band = BandNetherStructures.open(context, FOOTPRINT_RADIUS);
            if (band == null) return Optional.empty();

            WorldgenRandom random = context.random();
            RuinedPortalStructure.Setup setup = pickSetup(setups, random);
            if (setup.placement() != RuinedPortalPiece.VerticalPlacement.IN_NETHER) return Optional.empty();

            RuinedPortalPiece.Properties properties = new RuinedPortalPiece.Properties();
            properties.airPocket = sample(random, setup.airPocketProbability());
            properties.mossiness = setup.mossiness();
            properties.overgrown = setup.overgrown();
            properties.vines = setup.vines();
            properties.replaceWithBlackstone = setup.replaceWithBlackstone();
            // cold is left false: the band's core is the Nether, which never snows.

            ResourceLocation templateId = random.nextFloat() < PROBABILITY_OF_GIANT_PORTAL
                    ? ResourceLocation.withDefaultNamespace(GIANT_PORTALS[random.nextInt(GIANT_PORTALS.length)])
                    : ResourceLocation.withDefaultNamespace(PORTALS[random.nextInt(PORTALS.length)]);

            StructureTemplate template = context.structureTemplateManager().getOrCreate(templateId);
            Rotation rotation = Util.getRandom(Rotation.values(), random);
            Mirror mirror = random.nextFloat() < 0.5F ? Mirror.NONE : Mirror.FRONT_BACK;
            BlockPos pivot = new BlockPos(template.getSize().getX() / 2, 0, template.getSize().getZ() / 2);
            BlockPos chunkOrigin = context.chunkPos().getWorldPosition();
            BoundingBox box = template.getBoundingBox(chunkOrigin, rotation, pivot, mirror);

            if (!band.biomeAllows(biomes, box.getCenter().getX(), box.getCenter().getZ())) {
                return Optional.empty();
            }

            int y = findSuitableY(random, band, properties.airPocket, box);
            BlockPos start = new BlockPos(chunkOrigin.getX(), y, chunkOrigin.getZ());
            return Optional.of(new Structure.GenerationStub(start, builder -> builder.addPiece(
                    new RuinedPortalPiece(context.structureTemplateManager(), start, setup.placement(),
                            properties, templateId, template, rotation, mirror, pivot))));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Ruined-portal siting failed; leaving it to vanilla", t);
            return Optional.empty();
        }
    }

    /** Vanilla's weighted setup draw. */
    private static RuinedPortalStructure.Setup pickSetup(List<RuinedPortalStructure.Setup> setups,
                                                         WorldgenRandom random) {
        if (setups.size() == 1) return setups.get(0);
        float total = 0.0F;
        for (RuinedPortalStructure.Setup setup : setups) {
            total += setup.weight();
        }
        float roll = random.nextFloat();
        for (RuinedPortalStructure.Setup setup : setups) {
            roll -= setup.weight() / total;
            if (roll < 0.0F) return setup;
        }
        return setups.get(setups.size() - 1);
    }

    /**
     * Vanilla's {@code in_nether} height rule, with the corner probe reading the band's core terrain: roll a
     * Nether-space Y, translate it onto the band, then walk down until three of the footprint's four corners
     * are solid. If the whole column is open -- a lava lake or a cavern -- the walk bottoms out at the base
     * of the core slab, exactly as vanilla's bottoms out at its own minimum.
     */
    private static int findSuitableY(WorldgenRandom random, BandNetherStructures.Site band,
                                     boolean airPocket, BoundingBox box) {
        int netherY;
        if (airPocket) {
            netherY = Mth.randomBetweenInclusive(random, AIR_POCKET_MIN_NETHER_Y, MAX_NETHER_Y);
        } else if (random.nextFloat() < 0.5F) {
            netherY = Mth.randomBetweenInclusive(random, LOW_MIN_NETHER_Y, LOW_MAX_NETHER_Y);
        } else {
            netherY = Mth.randomBetweenInclusive(random, OPEN_MIN_NETHER_Y, MAX_NETHER_Y);
        }

        NetherCoreGeometry.Column[] corners = {
            band.column(box.minX(), box.minZ()),
            band.column(box.maxX(), box.minZ()),
            band.column(box.minX(), box.maxZ()),
            band.column(box.maxX(), box.maxZ())
        };

        int floor = band.geometry().minCoreY();
        for (int y = band.bandYInCore(netherY); y > floor; y--) {
            int solid = 0;
            for (NetherCoreGeometry.Column corner : corners) {
                if (corner.isSolid(y) && ++solid == REQUIRED_SOLID_CORNERS) return y;
            }
        }
        return floor;
    }

    private static boolean sample(WorldgenRandom random, float threshold) {
        if (threshold == 0.0F) return false;
        return threshold == 1.0F || random.nextFloat() < threshold;
    }
}
