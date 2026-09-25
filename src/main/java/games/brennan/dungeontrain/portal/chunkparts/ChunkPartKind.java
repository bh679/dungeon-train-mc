package games.brennan.dungeontrain.portal.chunkparts;

import games.brennan.dungeontrain.portal.PortalChunkTerrain;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;

import java.util.List;
import java.util.Locale;

/**
 * One of the four parts a chunk dimension's frame is built from — the same four a carriage shell is
 * ({@link CarriagePartKind}), grown to the size of a dimensional carriage and two blocks thick.
 *
 * <h2>Two layers, and they mean different things</h2>
 * <p>The frame is the two-block hull of the room box {@code [0,L)×[0,H)×[0,W)}: the <b>outer</b> layer
 * stands one block outside it, where the lock skin (the skybox) would be, and the <b>inner</b> layer
 * is the box's own edge row, where the sampled terrain runs. {@link ChunkPartPlacer} writes the two
 * differently — see there — but the geometry here does not care which is which.</p>
 *
 * <h2>Ownership follows the carriage parts</h2>
 * <p>Doors own the two ends across the whole cross-section, walls own the two sides between them,
 * floor and roof the inner rectangle. Together the four tile the hull exactly, no cell twice
 * ({@code ChunkPartKindTest}). Axes as everywhere: X length, Y height, Z width.</p>
 *
 * <p>Mirrored placements flip the template's own local axis rather than pivoting a
 * {@code StructureTemplate} about its origin: carriage parts get away with the origin pivot because
 * they are one block thick, and these are two. Local {@code x = 0} of a door and local {@code z = 0}
 * of a wall are always the outer layer, on either side.</p>
 */
public enum ChunkPartKind {
    FLOOR(CarriagePartKind.FLOOR),
    WALLS(CarriagePartKind.WALLS),
    ROOF(CarriagePartKind.ROOF),
    DOORS(CarriagePartKind.DOORS);

    /** The frame's thickness: one layer outside the box, one inside it. */
    public static final int THICKNESS = 2;

    /** The room box a frame fits — a dimensional carriage's own. */
    public static final int LENGTH = PortalChunkTerrain.SIZE;
    public static final int HEIGHT = PortalChunkTerrain.HEIGHT;
    public static final int WIDTH = PortalChunkTerrain.SIZE;

    /** The room size a frame fits, for callers that hold a box and want to know if it is one. */
    public static final Vec3i ROOM_SIZE = new Vec3i(LENGTH, HEIGHT, WIDTH);

    /**
     * One stamp of a part, {@code offset} from the room origin. {@code mirror} flips the template's
     * local X ({@link Mirror#FRONT_BACK}) or Z ({@link Mirror#LEFT_RIGHT}) and its blocks' facings.
     */
    public record Placement(BlockPos offset, Mirror mirror) {}

    private final CarriagePartKind carriageKind;

    ChunkPartKind(CarriagePartKind carriageKind) {
        this.carriageKind = carriageKind;
    }

    /**
     * The carriage part this one is the chunk-sized counterpart of — and the key a room's
     * {@code .parts.json} is read under, which is a {@link games.brennan.dungeontrain.train.CarriagePartAssignment}.
     */
    public CarriagePartKind carriageKind() {
        return carriageKind;
    }

    /** Directory and id token, e.g. {@code chunk_walls}. */
    public String id() {
        return "chunk_" + name().toLowerCase(Locale.ROOT);
    }

    /** The exact size a template of this kind must be. */
    public Vec3i size() {
        return switch (this) {
            case FLOOR, ROOF -> new Vec3i(LENGTH - 2, THICKNESS, WIDTH - 2);
            case WALLS -> new Vec3i(LENGTH - 2, HEIGHT + 2, THICKNESS);
            case DOORS -> new Vec3i(THICKNESS, HEIGHT + 2, WIDTH + 2);
        };
    }

    /** Where this kind is stamped, relative to the room origin. */
    public List<Placement> placements() {
        return switch (this) {
            case FLOOR -> List.of(new Placement(new BlockPos(1, -1, 1), Mirror.NONE));
            case ROOF -> List.of(new Placement(new BlockPos(1, HEIGHT - 1, 1), Mirror.NONE));
            case WALLS -> List.of(
                new Placement(new BlockPos(1, -1, -1), Mirror.NONE),
                new Placement(new BlockPos(1, -1, WIDTH - 1), Mirror.LEFT_RIGHT));
            case DOORS -> List.of(
                new Placement(new BlockPos(-1, -1, -1), Mirror.NONE),
                new Placement(new BlockPos(LENGTH - 1, -1, -1), Mirror.FRONT_BACK));
        };
    }

    /**
     * Room-local position of template cell {@code (lx, ly, lz)} under {@code placement}. The flip is
     * about the template's own extent, so a mirrored two-thick part lands on the same two layers.
     */
    public BlockPos roomLocal(Placement placement, int lx, int ly, int lz) {
        Vec3i size = size();
        int x = placement.mirror() == Mirror.FRONT_BACK ? size.getX() - 1 - lx : lx;
        int z = placement.mirror() == Mirror.LEFT_RIGHT ? size.getZ() - 1 - lz : lz;
        BlockPos o = placement.offset();
        return new BlockPos(o.getX() + x, o.getY() + ly, o.getZ() + z);
    }

    /** True when room-local {@code (x, y, z)} lies outside the room box — the frame's outer layer. */
    public static boolean isOuter(int x, int y, int z) {
        return x < 0 || x >= LENGTH || y < 0 || y >= HEIGHT || z < 0 || z >= WIDTH;
    }

    /** Parse an id ({@code chunk_walls}) or a bare kind name ({@code walls}), or null. */
    public static ChunkPartKind fromId(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (ChunkPartKind k : values()) {
            if (k.id().equals(key) || k.name().toLowerCase(Locale.ROOT).equals(key)) return k;
        }
        return null;
    }
}
