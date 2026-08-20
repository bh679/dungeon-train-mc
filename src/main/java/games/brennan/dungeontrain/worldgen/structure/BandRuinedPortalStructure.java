package games.brennan.dungeontrain.worldgen.structure;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Ruined portals in the Nether band's core — vanilla's nether-variant portals, blackstone and all,
 * anchored in the band's own netherrack.
 *
 * <p>Vanilla's own {@code in_nether} rule already ignores terrain height when it picks a Y: it rolls one
 * from a fixed Nether-space range (27–100, or 32–100 when the portal gets an air pocket) and then walks
 * <em>down</em> until three of the footprint's four corners are solid. Only that walk needs replacing —
 * vanilla reads it from the chunk generator's noise columns, which in the band describe the overworld
 * mountain the core replaces, so it reads {@link NetherCoreGeometry} instead. The roll, the template
 * choice, the rotation, the mirror and the piece are vanilla's.</p>
 *
 * <p>Only the Nether placements are supported: a setup asking for any other vertical placement is declined
 * rather than mis-sited, because the band's core is Nether and nothing else.</p>
 */
public class BandRuinedPortalStructure extends Structure {

    public static final MapCodec<BandRuinedPortalStructure> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    settingsCodec(instance),
                    ExtraCodecs.nonEmptyList(RuinedPortalStructure.Setup.CODEC.listOf())
                            .fieldOf("setups").forGetter(s -> s.setups)
            ).apply(instance, BandRuinedPortalStructure::new));

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

    /** Corners of the footprint that must be solid for the portal to rest there — vanilla's rule. */
    private static final int REQUIRED_SOLID_CORNERS = 3;

    /** A portal is a single template; it stays close to its own chunk. */
    private static final int FOOTPRINT_RADIUS = 32;

    private final List<RuinedPortalStructure.Setup> setups;

    public BandRuinedPortalStructure(StructureSettings settings, List<RuinedPortalStructure.Setup> setups) {
        super(settings);
        this.setups = setups;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        try {
            BandNetherStructures.Site site = BandNetherStructures.open(context, FOOTPRINT_RADIUS);
            if (site == null) return Optional.empty();

            WorldgenRandom random = context.random();
            RuinedPortalStructure.Setup setup = pickSetup(random);
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

            if (!site.biomeAllows(this.biomes(), box.getCenter().getX(), box.getCenter().getZ())) {
                return Optional.empty();
            }

            int y = findSuitableY(random, site, properties.airPocket, box);
            BlockPos start = new BlockPos(chunkOrigin.getX(), y, chunkOrigin.getZ());
            return Optional.of(new GenerationStub(start, builder -> builder.addPiece(
                    new RuinedPortalPiece(context.structureTemplateManager(), start, setup.placement(),
                            properties, templateId, template, rotation, mirror, pivot))));
        } catch (Throwable t) {
            SITE_ERRORS.error(LOGGER, "[DungeonTrain] Ruined-portal siting failed; skipping this portal", t);
            return Optional.empty();
        }
    }

    /** Vanilla's weighted setup draw. */
    private RuinedPortalStructure.Setup pickSetup(WorldgenRandom random) {
        if (this.setups.size() == 1) return this.setups.get(0);
        float total = 0.0F;
        for (RuinedPortalStructure.Setup setup : this.setups) {
            total += setup.weight();
        }
        float roll = random.nextFloat();
        for (RuinedPortalStructure.Setup setup : this.setups) {
            roll -= setup.weight() / total;
            if (roll < 0.0F) return setup;
        }
        return this.setups.get(this.setups.size() - 1);
    }

    /**
     * Vanilla's {@code in_nether} height rule, with the corner probe reading the band's core terrain: roll
     * a Nether-space Y, translate it onto the band, then walk down until three of the footprint's four
     * corners are solid. If the whole column is open — a lava lake or a cavern — the walk bottoms out at
     * the base of the core slab, exactly as vanilla's bottoms out at its own minimum.
     */
    private static int findSuitableY(WorldgenRandom random, BandNetherStructures.Site site,
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
            site.column(box.minX(), box.minZ()),
            site.column(box.maxX(), box.minZ()),
            site.column(box.minX(), box.maxZ()),
            site.column(box.maxX(), box.maxZ())
        };

        int floor = site.geometry().minCoreY();
        for (int y = site.bandYInCore(netherY); y > floor; y--) {
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

    /**
     * Vanilla's biome filter, deliberately skipped — {@link #findGenerationPoint} already applied the
     * Nether biome rule against the real Nether's own biome source, which is exact regardless of what the
     * overworld biome source reports for a column this far below sea level.
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext context) {
        return this.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return ModStructureTypes.RUINED_PORTAL_NETHER.get();
    }
}
