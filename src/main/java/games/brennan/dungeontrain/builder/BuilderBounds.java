package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The volumes a Train Builder is actually authoring: one box per parked carriage.
 *
 * <p>Whatever sits inside these boxes is the build — it's the region the carriage template is cut
 * from. Anything outside is scaffolding at best, so the client washes it red (see
 * {@code OutOfBoundsWashRenderer}) rather than letting you find out at save time.</p>
 *
 * <p>The flatbed pads that wrap a multi-carriage run are deliberately <b>not</b> in bounds: they
 * exist to make the silhouette read correctly from the platform, not to be built on.</p>
 *
 * <p>Derived from the same {@link BuilderWorldLayout} arithmetic {@code BuilderWorldSetup} stamps
 * from, so the boxes can't drift away from where the carriages actually are.</p>
 */
public final class BuilderBounds {

    private BuilderBounds() {}

    /** One box per carriage, in the order they're stamped (lowest X first). Empty for 0 carriages. */
    public static List<BoundingBox> buildVolumes(int carriages, CarriageDims dims) {
        List<BoundingBox> boxes = new ArrayList<>(Math.max(0, carriages));
        if (carriages <= 0) {
            return boxes;
        }
        int startX = BuilderWorldLayout.trainStartX(carriages, dims);
        int enclosedX = BuilderWorldLayout.usesPads(carriages)
                ? startX + games.brennan.dungeontrain.train.CarriagePlacer.halfPadLen(dims)
                : startX;

        for (int i = 0; i < carriages; i++) {
            int x0 = enclosedX + i * dims.length();
            boxes.add(new BoundingBox(
                    x0, BuilderWorldLayout.TRAIN_Y, 0,
                    x0 + dims.length() - 1,
                    BuilderWorldLayout.TRAIN_Y + dims.height() - 1,
                    dims.width() - 1));
        }
        return boxes;
    }

    /**
     * The one box a portal room occupies, or empty when {@code size} is null.
     *
     * <p>The second shape this class has to describe, and it differs from a carriage run on both
     * counts that matter: there is exactly one box, and its size is the <b>author's</b> rather than
     * {@link CarriageDims}'. Every consumer of this list therefore has to take a volume's extent
     * from the box it was handed and not from {@code dims} — see {@link #sizeOf}.</p>
     */
    public static List<BoundingBox> roomVolume(Vec3i size) {
        if (size == null) {
            return List.of();
        }
        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(size);
        return List.of(new BoundingBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX() - 1,
                origin.getY() + size.getY() - 1,
                origin.getZ() + size.getZ() - 1));
    }

    /**
     * What this builder world is authoring right now: a portal room's box, or the carriage run.
     *
     * <p>The single seam the whole feature turns on. {@code BuilderSave}, {@code BuilderDirtyCheck},
     * {@code BuilderBoundsPacket}, the cinematic and the plot lookup all ask this one question, so
     * teaching it the room shape is what makes Save, the dirty light and the out-of-bounds wash
     * follow a room without any of them knowing what a portal room is.</p>
     */
    public static List<BoundingBox> volumesFor(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        BuilderMode mode = BuilderMode.fromId(data.builderMode()).orElse(null);
        if (mode == BuilderMode.TRAIN_DIMENSIONS
                && BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType())) {
            String name = data.builderName();
            // No name means no room is open — an empty list, which every consumer already reads as
            // "nothing to save, nothing in bounds".
            return name == null || name.isEmpty()
                    ? List.of()
                    : roomVolume(PortalRoomSizes.sizeOf(name, dims));
        }
        // What is actually parked, not what the mode would park for a whole carriage: opening a room
        // or a part parks one carriage, and asking the mode would cut three volumes out of a
        // one-carriage world.
        return buildVolumes(BuilderWorldSetup.parkedCarriages(data), dims);
    }

    /**
     * A volume's extent, taken from the box rather than from {@link CarriageDims}.
     *
     * <p>Exists because every caller used to pass {@code dims.length(), dims.height(),
     * dims.width()} alongside a box, which was the same numbers by construction while every volume
     * was a carriage. A room's box is the author's size, so the two have parted company and reading
     * {@code dims} would snapshot a 48-long room through an 11-block window.</p>
     */
    public static Vec3i sizeOf(BoundingBox box) {
        return new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan());
    }

    /** The lowest corner of {@code box}, the origin its snapshot and capture are relative to. */
    public static BlockPos originOf(BoundingBox box) {
        return new BlockPos(box.minX(), box.minY(), box.minZ());
    }

    /** True when {@code pos} is inside any build volume. An empty list means nothing is in bounds. */
    public static boolean isInsideBuild(BlockPos pos, List<BoundingBox> volumes) {
        for (BoundingBox box : volumes) {
            if (box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }
}
