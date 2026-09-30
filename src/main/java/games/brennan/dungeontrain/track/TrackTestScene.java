package games.brennan.dungeontrain.track;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.template.TemplateGroup;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGroupRoll;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stamps one stretch of a Tracks-category Test the Carriage — columns in the open with a staircase
 * beside the middle one, then a tunnel with a shaft up through its first section — laid out by
 * {@link TrackTestLayout}.
 *
 * <p><b>The generator's stamping, not a copy of it.</b> Tiles, pillar slices, arch tapers,
 * staircases and tunnel pieces all go down through the same {@link TrackGenerator} and
 * {@link TunnelPlacer} code the world uses; only the <i>choice</i> differs. The world picks every
 * name by world X; here the piece under test is named outright wherever it appears, and everything
 * else is picked the world's way on the scene's seed, gated to a {@link TrackTestBand} the piece
 * could appear in — a test is at no place on the track for the band to be read from.</p>
 */
public final class TrackTestScene {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The floor the columns stand on. */
    private static final BlockState GROUND = Blocks.GRASS_BLOCK.defaultBlockState();

    /** What the tunnel stands on — it is always cut through ground in the world, never on stilts. */
    private static final BlockState EMBANKMENT = Blocks.STONE.defaultBlockState();

    private TrackTestScene() {}

    /**
     * What one stretch rolls on: the piece under test and its name, the seed its block variants roll
     * on, the seed everything else is picked and rolled on, and the band those picks are made in.
     * The seeds are kept apart so a focused reseed can re-roll the piece and leave the line around it
     * standing.
     */
    public record Roll(TrackTestPiece piece, String name, long testSeed, long sceneSeed, TrackTestBand band,
                       TemplateGroup forcedGroup) {

        /** A piece test — no group forced; the tunnel follows the tested piece's groups. */
        public Roll(TrackTestPiece piece, String name, long testSeed, long sceneSeed, TrackTestBand band) {
            this(piece, name, testSeed, sceneSeed, band, null);
        }

        /** The name at {@code index}: the one under test, or the world's pick in the band on the scene's seed. */
        String nameFor(TrackKind kind, long index) {
            if (kind == piece.kind()) return name;
            return TrackVariantRegistry.pickName(kind, sceneSeed, index, band.context());
        }

        /** {@link #nameFor}, drawn from the tunnel's {@code group} — so the test tunnel is one set end to end. */
        String nameFor(TrackKind kind, long index, TemplateGroup group) {
            // A group test pins no piece: every section and entrance is the group's draw.
            if (kind == piece.kind() && forcedGroup == null) return name;
            return TrackVariantRegistry.pickName(kind, sceneSeed, index, band.context(), group);
        }

        /**
         * The template group the test tunnel is built from, as a tunnel in the world would be. Testing
         * a tunnel section or entrance, it is one of the groups that template belongs to (the
         * ungrouped pool if none), so every other piece around it comes from the same set; testing
         * anything else, the tunnel rolls its group the world's way. Null = no group filter.
         */
        TemplateGroup tunnelGroup() {
            if (forcedGroup != null) return forcedGroup;
            if (piece.kind() == TrackKind.TUNNEL_SECTION || piece.kind() == TrackKind.TUNNEL_PORTAL) {
                return TunnelGroupRoll.rollAmong(sceneSeed, 0, band.context(),
                    TrackVariantWeights.groupsFor(piece.kind(), name));
            }
            return TunnelGroupRoll.roll(sceneSeed, 0, band.context());
        }

        /** The seed a kind's block variants roll on. */
        long seedFor(TrackKind kind) {
            return kind == piece.kind() ? testSeed : sceneSeed;
        }

        /** A staircase and its entrance are one structure, so they roll together. */
        long stairsSeed() {
            boolean tested = piece == TrackTestPiece.STAIRS || piece == TrackTestPiece.STAIRS_ENTRANCE;
            return tested ? testSeed : sceneSeed;
        }
    }

    /** The line's geometry for a stretch cornered at {@code corner}. */
    public static TrackGeometry geometry(BlockPos corner, CarriageDims dims) {
        int bedY = corner.getY() + TrackTestLayout.bedY();
        return new TrackGeometry(bedY, bedY + 1, corner.getZ(), corner.getZ() + dims.width() - 1);
    }

    /**
     * Stamp one stretch. {@code corner} is the layout's origin: the first free row over the floor, at
     * the corridor's first Z. The caller wraps this in {@code CarriageStampGuard.run}.
     */
    public static void stampStretch(ServerLevel level, BlockPos corner, CarriageDims dims,
                                    TrackTestLayout layout, Roll roll) {
        TrackGeometry g = geometry(corner, dims);
        stampGround(level, corner, layout);
        stampEmbankment(level, corner, layout, g);
        stampColumns(level, corner, dims, layout, g, roll);
        stampTrack(level, corner, dims, layout, g, roll);
        stampTunnel(level, corner, layout, g, roll);
        stampDownStairs(level, corner, layout, g, roll);
    }

    private static void stampGround(ServerLevel level, BlockPos corner, TrackTestLayout layout) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = corner.getY() - 1;
        for (int dx = 0; dx < layout.stretchLength(); dx++) {
            for (int dz = TrackTestLayout.minZ(); dz <= layout.maxZ(); dz++) {
                pos.set(corner.getX() + dx, y, corner.getZ() + dz);
                level.setBlock(pos, GROUND, Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Solid ground under the tunnel's footprint, up to the bed. */
    private static void stampEmbankment(ServerLevel level, BlockPos corner, TrackTestLayout layout,
                                        TrackGeometry g) {
        int zMin = TunnelGeometry.from(g).wallMinZ() + 1;
        int x0 = corner.getX() + layout.tunnelX();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < TrackTestLayout.tunnelLength(); dx++) {
            for (int y = corner.getY(); y < g.bedY(); y++) {
                for (int dz = 0; dz < TunnelPlacer.WIDTH; dz++) {
                    pos.set(x0 + dx, y, zMin + dz);
                    level.setBlock(pos, EMBANKMENT, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static void stampColumns(ServerLevel level, BlockPos corner, CarriageDims dims,
                                     TrackTestLayout layout, TrackGeometry g, Roll roll) {
        int groundY = corner.getY();
        for (int centre : layout.columnCentres()) {
            int centreX = corner.getX() + centre;
            TrackGenerator.PillarPaint top = paint(level, PillarSection.TOP, dims, roll, centreX);
            TrackGenerator.PillarPaint mid = paint(level, PillarSection.MIDDLE, dims, roll, centreX);
            TrackGenerator.PillarPaint bot = paint(level, PillarSection.BOTTOM, dims, roll, centreX);
            int minX = corner.getX() + layout.columnMinX(centre);
            int maxX = corner.getX() + layout.columnMaxX(centre);
            for (int x = minX; x <= maxX; x++) {
                TrackGenerator.stampPillarSliceWorldgen(level, x, g, groundY, top, mid, bot);
            }
            TrackGenerator.stampArchTaperWorldgen(level, minX, maxX, TrackTestLayout.COLUMN_HEIGHT, g, top, mid);
            if (centre == layout.stairsColumn()) stampUpStairs(level, centreX, groundY, g, roll);
        }
    }

    private static TrackGenerator.PillarPaint paint(ServerLevel level, PillarSection section, CarriageDims dims,
                                                    Roll roll, int centreX) {
        TrackKind kind = PillarTemplateStore.pillarKind(section);
        return TrackGenerator.pillarPaintNamed(level, section, dims, roll.nameFor(kind, centreX),
            roll.seedFor(kind), centreX);
    }

    /** The pillar staircase, on the +Z side, from the deck down to the floor. */
    private static void stampUpStairs(ServerLevel level, int centreX, int groundY, TrackGeometry g, Roll roll) {
        String name = roll.nameFor(TrackKind.ADJUNCT_STAIRS, centreX);
        Optional<StructureTemplate> template = PillarTemplateStore.getAdjunctFor(level, PillarAdjunct.STAIRS, name);
        if (template.isEmpty()) {
            LOGGER.info("[DungeonTrain] track test: no stairs template '{}' — the column stands without one", name);
            return;
        }
        TrackGenerator.stampStairsBesidePillarWorldgen(level, template.get(), TrackGenerator.stairsSidecar(name),
            centreX, groundY, g, roll.stairsSeed(), /*flipped*/ false);
    }

    /** Bed and rails the whole stretch long — under the tunnel too, which re-stamps its own. */
    private static void stampTrack(ServerLevel level, BlockPos corner, CarriageDims dims, TrackTestLayout layout,
                                   TrackGeometry g, Roll roll) {
        Map<Long, TrackGenerator.TilePaint> paints = new HashMap<>();
        long seed = roll.seedFor(TrackKind.TILE);
        for (int dx = 0; dx < layout.stretchLength(); dx++) {
            int x = corner.getX() + dx;
            long idx = Math.floorDiv(x, TrackTestLayout.TILE_LENGTH);
            TrackGenerator.TilePaint paint = paints.computeIfAbsent(idx,
                i -> TrackGenerator.tilePaintNamed(level, dims, roll.nameFor(TrackKind.TILE, i), seed, i));
            for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
                TrackGenerator.placeTrackColumn(level, null, x, z, g, paint, null);
            }
        }
    }

    private static void stampTunnel(ServerLevel level, BlockPos corner, TrackTestLayout layout, TrackGeometry g,
                                    Roll roll) {
        int z = TunnelGeometry.from(g).wallMinZ() + 1;
        TemplateGroup group = roll.tunnelGroup();
        for (TrackTestLayout.TunnelPiece piece : layout.tunnelPieces()) {
            int x = corner.getX() + piece.x();
            BlockPos at = new BlockPos(x, g.bedY(), z);
            if (piece.portal()) {
                TunnelPlacer.placePortalNamed(level, at, piece.mirrored(),
                    roll.nameFor(TrackKind.TUNNEL_PORTAL, x, group), roll.seedFor(TrackKind.TUNNEL_PORTAL), x);
            } else {
                TunnelPlacer.placeSectionNamed(level, at,
                    roll.nameFor(TrackKind.TUNNEL_SECTION, x, group), roll.seedFor(TrackKind.TUNNEL_SECTION), x);
            }
        }
    }

    /** A shaft up through the first section on the −Z side, capped by its entrance on the tunnel's roof. */
    private static void stampDownStairs(ServerLevel level, BlockPos corner, TrackTestLayout layout, TrackGeometry g,
                                        Roll roll) {
        int centreX = corner.getX() + layout.downStairsCentre();
        String name = roll.nameFor(TrackKind.ADJUNCT_STAIRS, centreX);
        Optional<StructureTemplate> template = PillarTemplateStore.getAdjunctFor(level, PillarAdjunct.STAIRS, name);
        if (template.isEmpty()) {
            LOGGER.info("[DungeonTrain] track test: no stairs template '{}' — the tunnel stands without a shaft", name);
            return;
        }
        TrackGenerator.stampDownStairsWorldgen(level, level, template.get(), TrackGenerator.stairsSidecar(name),
            roll.nameFor(TrackKind.ADJUNCT_STAIRS_ENTRANCE, centreX), centreX, /*flipped*/ true,
            corner.getY() + TrackTestLayout.surfaceY(), g, roll.stairsSeed());
    }
}
