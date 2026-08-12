package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
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
     * <p>A third shape beside the carriage run and the track plot, and it differs from both on the
     * count that matters: its size is the <b>author's</b> rather than {@link CarriageDims}'. Every
     * consumer therefore has to take a volume's extent from the box it was handed and not from
     * {@code dims} — see {@link #sizeOf}.</p>
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
     * The single box a track-side build is authored in, or empty when this isn't one.
     *
     * <p>One box where a carriage build gets one per carriage, because a track template is one
     * template — you open a tunnel, not a run of three. Same list type all the same, so every
     * downstream consumer (the wash renderer, the dirty check, the bounds packet) keeps taking the
     * same shape and needs no track-specific arm of its own.</p>
     */
    public static List<BoundingBox> trackVolumes(TrackKind kind, CarriageDims dims) {
        return kind == null ? List.of() : List.of(BuilderTrackPlot.volume(kind, dims));
    }

    /**
     * The volumes this builder world is authoring right now, whichever kind of build it holds.
     *
     * <p>The one call every consumer should make: a track build and a carriage build differ in where
     * the boxes come from and in nothing else, and spreading that fork across four callers is how
     * they drift.</p>
     *
     * <p>Takes the count that was actually parked rather than deriving one from the mode. Those are
     * different questions — opening a room parks one carriage where its mode would park three — and
     * cutting three boxes out of a one-carriage world reads two templates' worth of empty air.</p>
     */
    public static List<BoundingBox> volumesFor(int parkedCarriages, TrackKind trackKind,
                                               CarriageDims dims) {
        if (parkedCarriages > 0) {
            return buildVolumes(parkedCarriages, dims);
        }
        return trackVolumes(trackKind, dims);
    }

    /**
     * The same question, read off the world — including the one answer the pure form cannot give.
     *
     * <p>A portal room's box is sized by the room, so it cannot be derived from a carriage count or a
     * track kind; it needs the open room's name and {@link PortalRoomSizes}. Everything else defers
     * to {@link #volumesFor(int, TrackKind, CarriageDims)}.</p>
     */
    public static List<BoundingBox> volumesFor(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        if (BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType())) {
            String name = data.builderName();
            // No name means no room is open — an empty list, which every consumer already reads as
            // "nothing to save, nothing in bounds".
            return name == null || name.isEmpty()
                    ? List.of()
                    : roomVolume(PortalRoomSizes.sizeOf(name, dims));
        }
        return volumesFor(BuilderWorldSetup.parkedCarriages(data),
                BuilderTrackBuild.kindOf(data), dims);
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
