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
 * <p>The masked volume is deliberately a little larger than the corridor's own box. It runs the full
 * width and height of the <i>room</i> across each corridor's X range, because that slab is exactly
 * what {@code PortalCarriageBuilder.stampCorridors} writes: the corridor itself, plus the seal ring
 * that walls off everything around its mouth. A copy stamped over the seal would open a hole into the
 * rock beside the door.</p>
 *
 * <p>What a player sees is unchanged by any of this: along the train's axis an endless room is broken
 * by a wall with a door in it — the way back — and the room carries on beyond it.</p>
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

    /** True when {@code (x, y, z)} belongs to a corridor and must be left alone. */
    public boolean covers(int x, int y, int z) {
        for (BoundingBox box : boxes) {
            if (box.isInside(new Vec3i(x, y, z))) return true;
        }
        return false;
    }

    public boolean covers(BlockPos pos) {
        return covers(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * The mask for {@code structure}: a full-height, full-room-width slab across each corridor's X
     * range, plus the plug beyond each outer door.
     *
     * @param plugDepth how far a plug reaches past its corridor — {@code PortalCarriageBuilder}'s
     *                  own constant, passed in so the two cannot disagree about it
     */
    public static PortalCorridorMask forStructure(PortalStructure structure, CarriageDims dims,
                                                  PortalCarriageLayout layout, int plugDepth) {
        BlockPos origin = structure.origin();
        BlockPos exit = structure.exitOrigin(dims);
        BlockPos room = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();

        // Inclusive bounds, sized to exactly what stampCorridors writes and no further. The seal ring
        // is the widest of it: it fills the room's own cross-section at each door plane, so the slab
        // is the room's Z and Y extent — but stopping one short of the next row, or a copy standing
        // beside the corridors would have its first column silently skipped.
        int minZ = Math.min(room.getZ(), origin.getZ());
        int maxZ = Math.max(room.getZ() + size.getZ() - 1, origin.getZ() + dims.width() - 1);
        int minY = origin.getY();
        int maxY = origin.getY() + Math.max(dims.height(), size.getY()) - 1;

        return new PortalCorridorMask(List.of(
            // Entry corridor and its seal ring, then the plug behind its dead door.
            new BoundingBox(origin.getX() - plugDepth, minY, minZ,
                origin.getX() + dims.length() - 1, maxY, maxZ),
            // Exit corridor and its seal ring, then the plug beyond its dead door.
            new BoundingBox(exit.getX(), minY, minZ,
                exit.getX() + dims.length() - 1 + plugDepth, maxY, maxZ)));
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
