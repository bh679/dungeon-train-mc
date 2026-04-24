package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;

import java.util.List;
import java.util.Locale;

/**
 * One of the four reusable part templates a carriage shell is composed from.
 * A parts-backed carriage (i.e. one whose {@code <id>.parts.json} sidecar
 * declares per-kind part names) stamps FLOOR, WALLS, ROOF, and DOORS in turn
 * at predictable positions within its {@link CarriageDims} footprint instead
 * of stamping the monolithic NBT in {@code config/dungeontrain/templates/}.
 *
 * <p>Geometry (axes X=length, Y=height, Z=width):
 * <ul>
 *   <li>{@link #FLOOR} — {@code (length-2) × 1 × (width-2)}, placed once at
 *       {@code (1, 0, 1)}. Inner rectangle at bottom; the surrounding perimeter
 *       at {@code y=0} is owned by walls and doors.</li>
 *   <li>{@link #ROOF} — same size as floor, placed once at
 *       {@code (1, height-1, 1)}.</li>
 *   <li>{@link #WALLS} — {@code (length-2) × height × 1}, placed twice:
 *       unmirrored at {@code (1, 0, 0)}, and at {@code (1, 0, width-1)} with
 *       {@link Mirror#LEFT_RIGHT} (flips Z so block rotations face the carriage
 *       interior from either side).</li>
 *   <li>{@link #DOORS} — {@code 1 × height × width}, placed twice:
 *       unmirrored at {@code (0, 0, 0)}, and at {@code (length-1, 0, 0)} with
 *       {@link Mirror#FRONT_BACK} (flips X for the opposite end cap).</li>
 * </ul>
 *
 * <p>Together the four parts cover the carriage's outer shell with no
 * overlaps: doors own the corner columns at {@code x∈{0,length-1}} (full
 * width × height), walls own the z-edges for the interior x-range, and floor
 * plus roof own the inner rectangle at {@code y=0} and {@code y=height-1}.
 * Interior voxels are left untouched so players can furnish carriages
 * independently (consistent with how the monolithic templates currently
 * capture air inside).</p>
 *
 * <p>The mirror semantics are vanilla Minecraft's: {@code LEFT_RIGHT} flips
 * the Z axis (and NORTH↔SOUTH block facings); {@code FRONT_BACK} flips the
 * X axis (and EAST↔WEST facings). Because a wall NBT is 1-thick on Z and a
 * door NBT is 1-thick on X, the mirror pivot stays at the placement origin —
 * no {@code size-1} offset needed on the mirrored axis.</p>
 */
public enum CarriagePartKind {
    FLOOR,
    WALLS,
    ROOF,
    DOORS;

    /**
     * One in-carriage stamp of a part template. {@link #originOffset} is added
     * to the carriage's own {@code origin} to produce the template's placement
     * origin; {@link #mirror} is threaded into {@code StructurePlaceSettings}
     * so vanilla handles the block-rotation flips.
     */
    public record Placement(BlockPos originOffset, Mirror mirror) {}

    /** Sentinel used in {@code <variant>.parts.json} to mean "stamp nothing for this kind". */
    public static final String NONE = "none";

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * NBT footprint of a part template authored at the supplied world dims.
     * The template file's {@code StructureTemplate.getSize()} must equal this
     * exactly; {@link games.brennan.dungeontrain.editor.CarriagePartTemplateStore}
     * rejects templates with the wrong size and the caller falls back to
     * stamping nothing (so the part becomes a no-op rather than corrupting the
     * carriage).
     */
    public Vec3i dims(CarriageDims worldDims) {
        return switch (this) {
            case FLOOR, ROOF -> new Vec3i(worldDims.length() - 2, 1, worldDims.width() - 2);
            case WALLS       -> new Vec3i(worldDims.length() - 2, worldDims.height(), 1);
            case DOORS       -> new Vec3i(1, worldDims.height(), worldDims.width());
        };
    }

    /**
     * Stamp positions for this kind within a carriage rooted at
     * {@code carriageOrigin}. Each entry's {@link Placement#originOffset} is
     * relative to the carriage origin; callers add them before passing to
     * {@code StructureTemplate.placeInWorld}.
     */
    public List<Placement> placements(CarriageDims d) {
        return switch (this) {
            case FLOOR -> List.of(
                new Placement(new BlockPos(1, 0, 1), Mirror.NONE)
            );
            case ROOF  -> List.of(
                new Placement(new BlockPos(1, d.height() - 1, 1), Mirror.NONE)
            );
            case WALLS -> List.of(
                new Placement(new BlockPos(1, 0, 0), Mirror.NONE),
                new Placement(new BlockPos(1, 0, d.width() - 1), Mirror.LEFT_RIGHT)
            );
            case DOORS -> List.of(
                new Placement(new BlockPos(0, 0, 0), Mirror.NONE),
                new Placement(new BlockPos(d.length() - 1, 0, 0), Mirror.FRONT_BACK)
            );
        };
    }

    /** Parse a lowercase id back to a kind, or null if unrecognised. */
    public static CarriagePartKind fromId(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        for (CarriagePartKind k : values()) {
            if (k.id().equals(key)) return k;
        }
        return null;
    }
}
