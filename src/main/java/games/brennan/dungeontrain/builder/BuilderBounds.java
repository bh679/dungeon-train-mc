package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
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
     */
    public static List<BoundingBox> volumesFor(BuilderMode mode, TrackKind trackKind,
                                               CarriageDims dims) {
        if (mode != null && mode.carriageCount() > 0) {
            return buildVolumes(mode.carriageCount(), dims);
        }
        return trackVolumes(trackKind, dims);
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
