package games.brennan.dungeontrain.portal;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;

/**
 * The world volume a pair's two corridors own, which nothing else may write into.
 *
 * <p><b>Why this exists.</b> The endless modes tile copies of the room straight through the row the
 * twin corridors stand in. The first attempt let a copy overwrite the corridor and then stamped the
 * corridor back on top — correct, but it meant re-laying both twins, their seal rings and their plugs
 * every time a copy on that row appeared or retired, over and over for the life of a visit. A twin is
 * placed <b>once</b>, when its structure is built, and this is what keeps it that way: every later
 * write that would land inside a corridor is simply skipped, so there is nothing to repair.</p>
 *
 * <p><b>Exactly what {@code stampCorridors} writes, and not a block more.</b> That is three volumes
 * per corridor: the corridor's own box, the plug behind its outer door, and the seal ring — which is
 * a <i>single</i> plane at the corridor's mouth, filling the room's whole cross-section there.</p>
 *
 * <p>It used to be one slab per corridor instead, the room's full width and height across the
 * corridor's entire length, sized to the widest thing in it. That reserved a volume nothing ever
 * wrote into, and reserving it is the same as leaving it empty: a copy landing on the corridor row
 * put down no floor, no ceiling and no walls over that span, so the aisles either side of a corridor
 * were void with the corridor hanging in them — and at the default dims a corridor (13) is longer
 * than the built-in room (11), so the copy that lands on it stamped <i>nothing at all</i>. The seam
 * carve still opened the way in from the copy next door, which is how a player found it. Masking the
 * three real volumes lets a copy lay its floor, its ceiling and its own outer walls around the
 * corridor, so the corridor stands as an object inside the endless room — which is what
 * {@link DimensionalCarriageMode}'s javadoc has always said it is.</p>
 *
 * <p>What a player sees along the train's axis is unchanged: an endless room is broken by a wall with
 * a door in it — the way back — and the room carries on beyond it. That wall is the seal plane, and
 * it stays the room's full cross-section on purpose. It is the only thing between the room and the
 * rock when a copy cannot be stamped at all, which is normal and silent — the budget is spent, or the
 * chunks are not loaded ({@code DimensionalCarriageTiler.canStamp}). What it is <i>made of</i> is the room's
 * own wall rather than a palette of its own — {@code PortalCarriageBuilder.sealFillFor} — so the
 * wall reads as the room's, and only the door in it says anything.</p>
 */
public record PortalCorridorMask(List<BoundingBox> boxes) {

    /** Nothing masked — what every row but the corridor row uses. */
    public static final PortalCorridorMask NONE = new PortalCorridorMask(List.of());

    public PortalCorridorMask {
        boxes = List.copyOf(boxes);
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    /**
     * This mask with one more box in it.
     *
     * <p>What {@link DimensionalCarriageMode#ENDLESS_OPEN} adds to a tile's <b>write</b> mask: the tile's
     * interior, so the stamp puts down a floor and a roof and skips everything between them. Kept a
     * union rather than a replacement because the corridor row's tile needs both boxes — the
     * corridors it must stamp around, and the interior it must not fill.</p>
     */
    public PortalCorridorMask plus(BoundingBox box) {
        List<BoundingBox> grown = new java.util.ArrayList<>(boxes.size() + 1);
        grown.addAll(boxes);
        grown.add(box);
        return new PortalCorridorMask(grown);
    }

    /**
     * This mask unioned with another.
     *
     * <p>What an endless room's extra corridors need: every copy standing near a tile protects its
     * own three volumes, and the tile has to be stamped around all of them at once. Kept a union for
     * the same reason {@link #plus(BoundingBox)} is — the corridor row's tile needs the base pair's
     * boxes as well as any copy's.</p>
     */
    public PortalCorridorMask plus(PortalCorridorMask other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        List<BoundingBox> grown = new java.util.ArrayList<>(boxes.size() + other.boxes.size());
        grown.addAll(boxes);
        grown.addAll(other.boxes);
        return new PortalCorridorMask(grown);
    }

    /** True when any of this mask's boxes overlaps {@code box}. */
    public boolean intersects(BoundingBox box) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).intersects(box)) return true;
        }
        return false;
    }

    /** The smallest box containing everything this mask protects, or {@code null} when it is empty. */
    public BoundingBox bounds() {
        BoundingBox all = null;
        for (BoundingBox box : boxes) {
            all = all == null ? box : all.encapsulate(box);
        }
        return all;
    }

    /**
     * True when {@code (x, y, z)} belongs to a corridor and must be left alone.
     *
     * <p>Compares the bounds directly rather than through {@code BoundingBox.isInside(Vec3i)}, which
     * would allocate a vector per box per cell — this is called once for every cell of every stamp
     * and every erase.</p>
     */
    public boolean covers(int x, int y, int z) {
        for (int i = 0; i < boxes.size(); i++) {
            BoundingBox box = boxes.get(i);
            if (x >= box.minX() && x <= box.maxX()
                && y >= box.minY() && y <= box.maxY()
                && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    public boolean covers(BlockPos pos) {
        return covers(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * True when {@code wall} looks straight into a corridor one step along {@code (dx, dz)} — that is,
     * when the cell beyond it is masked even though the cell itself is not.
     *
     * <p>The room's own end column is like that: it sits one block inside a corridor's door plane, so
     * it is outside the mask, but what stands in front of it is the door. Walling it is walling the
     * way in, and carving it open buys nothing the door does not already give. Such a column belongs
     * to the corridor as surely as the corridor's own blocks do, and is left exactly as the room's
     * author wrote it.</p>
     */
    public boolean facedBy(BlockPos wall, int dx, int dz) {
        return covers(wall.getX() + dx, wall.getY(), wall.getZ() + dz);
    }

    /**
     * The mask for {@code structure}: three boxes per corridor — the corridor's own box, its plug,
     * and the single plane its seal ring fills.
     *
     * <p>Each is sized off the writer it protects, so the two cannot drift apart:
     * {@code stampTwin} clears and stamps the corridor's box; {@code plugBeyond} runs one column
     * proud of it on each side in Z; {@code sealCorridorMouth} sweeps the <b>room's</b> cross-section
     * at one X. Everything outside those is the room's to build in, which is what puts a floor in the
     * aisles beside a corridor.</p>
     *
     * @param plugDepth how far a plug reaches past its corridor — {@code PortalCarriageBuilder}'s
     *                  own constant, passed in so the two cannot disagree about it
     */
    public static PortalCorridorMask forStructure(PortalStructure structure, CarriageDims dims,
                                                  PortalCarriageLayout layout, int plugDepth) {
        // Each half off its own coordinate frame. The entry always stands beside the base room; the
        // exit stands beside whichever tile this pair put it at, and its seal ring has to fill THAT
        // room's cross-section rather than the base one's. Reading both off the same structure was
        // right only while an exit could not move.
        return forCorridor(structure, dims, layout, plugDepth, PortalCarriageRole.ENTRY)
            .plus(forCorridor(structure.exitShadow(), dims, layout, plugDepth,
                PortalCarriageRole.EXIT));
    }

    /**
     * The three boxes one end of a pair owns: the corridor's own box, the plug beyond its dead outer
     * door, and the single plane its seal ring fills at its mouth.
     *
     * <p>Split out of {@link #forStructure} so an endless room's <b>extra</b> corridors can be
     * masked. A copy is one corridor, not a pair — {@link PortalExitSites.Site} carries a role — and
     * it is stamped from the same structure translated onto its anchor tile
     * ({@link PortalStructure#shadowAt}), so passing that shadow here produces exactly the boxes
     * {@code stampCorridorHalf} writes at exactly the place it writes them. The two cannot drift
     * apart because they are the same geometry read twice.</p>
     */
    public static PortalCorridorMask forCorridor(PortalStructure structure, CarriageDims dims,
                                                 PortalCarriageLayout layout, int plugDepth,
                                                 PortalCarriageRole role) {
        BlockPos corridor = role == PortalCarriageRole.ENTRY
            ? structure.origin()
            : structure.exitOrigin(dims);
        BlockPos room = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();

        // The corridor's own cross-section. A room is never narrower or shorter than this
        // (DimensionalCarriageLayout.minWidth / minHeight), so a copy always has room either side of it.
        int corridorMinZ = corridor.getZ();
        int corridorMaxZ = corridor.getZ() + dims.width() - 1;
        int corridorMinY = corridor.getY();
        int corridorMaxY = corridor.getY() + dims.height() - 1;

        // The seal ring's, which is the room's — wider and, in a tall room, taller.
        int roomMinZ = room.getZ();
        int roomMaxZ = room.getZ() + size.getZ() - 1;
        // A room's floor is laid at the corridor's, by construction (DimensionalCarriageLayout.roomOrigin), so
        // this is the same row corridorMinY is — read off the room anyway, since it is the room's
        // cross-section the seal fills.
        int roomMinY = room.getY();
        int roomMaxY = room.getY() + size.getY() - 1;

        // The CORRIDOR's length, off the layout — a corridor is longer than the carriage it is placed
        // in (PortalCorridorSize), and a box sized to the carriage would leave each twin's inner tail
        // unprotected for a room copy to overwrite.
        int corridorLength = layout.length();
        int minX = corridor.getX();
        int maxX = minX + corridorLength - 1;
        // The mouth is the door plane facing the room: the far end of an entry corridor, the near end
        // of an exit one. The plug is always at the other end, behind the door that leads nowhere.
        boolean entry = role == PortalCarriageRole.ENTRY;
        int sealX = entry ? maxX : minX;
        int plugMinX = entry ? minX - plugDepth : maxX + 1;
        int plugMaxX = entry ? minX - 1 : maxX + plugDepth;

        return new PortalCorridorMask(List.of(
            new BoundingBox(minX, corridorMinY, corridorMinZ, maxX, corridorMaxY, corridorMaxZ),
            new BoundingBox(plugMinX, corridorMinY, corridorMinZ - 1,
                plugMaxX, corridorMaxY, corridorMaxZ + 1),
            new BoundingBox(sealX, roomMinY, roomMinZ, sealX, roomMaxY, roomMaxZ)));
    }

    /**
     * A {@link StructureProcessor} that drops any cell the mask covers, so a template stamp writes
     * around the corridors instead of through them.
     *
     * <p>Same shape as {@code PartRegionFilterProcessor}: returning {@code null} from
     * {@code processBlock} tells {@code StructureTemplate.placeInWorld} to leave that cell entirely
     * alone rather than writing air into it.</p>
     */
    public StructureProcessor asProcessor() {
        return new Filter(this);
    }

    /** Runtime-only, never serialised — the codec is a sentinel, as with the parts filter. */
    private static final class Filter extends StructureProcessor {

        private static final StructureProcessorType<Filter> TYPE =
            () -> MapCodec.unit(new Filter(NONE));

        private final PortalCorridorMask mask;

        private Filter(PortalCorridorMask mask) {
            this.mask = mask;
        }

        @Override
        public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader level, BlockPos origin, BlockPos pivot,
            StructureTemplate.StructureBlockInfo source,
            StructureTemplate.StructureBlockInfo target,
            StructurePlaceSettings settings
        ) {
            return mask.covers(target.pos()) ? null : target;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            return TYPE;
        }
    }
}
