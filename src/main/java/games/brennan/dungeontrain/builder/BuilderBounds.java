package games.brennan.dungeontrain.builder;

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
