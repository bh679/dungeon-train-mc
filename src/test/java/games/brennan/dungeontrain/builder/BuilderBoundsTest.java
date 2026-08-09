package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build volumes that decide what gets washed red.
 *
 * <p>These boxes have to line up exactly with where {@code BuilderWorldSetup.stampTrain} puts the
 * carriages — an off-by-one on either side means the builder sees the carriage they're standing
 * in flagged as out of bounds.</p>
 */
final class BuilderBoundsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("One box per carriage, laid end to end from the train start")
    void threeCarriagesProduceThreeAdjacentBoxes() {
        List<BoundingBox> boxes = BuilderBounds.buildVolumes(3, DIMS);
        assertEquals(3, boxes.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(DIMS.length(), boxes.get(i).getXSpan(), "carriage " + i + " length");
            assertEquals(DIMS.width(), boxes.get(i).getZSpan());
            assertEquals(DIMS.height(), boxes.get(i).getYSpan());
        }
        assertEquals(boxes.get(0).maxX() + 1, boxes.get(1).minX(), "carriages sit flush");
        assertEquals(boxes.get(1).maxX() + 1, boxes.get(2).minX());
    }

    @Test
    @DisplayName("The flatbed pads are outside the build — they frame it, they aren't part of it")
    void padsAreNotInBounds() {
        List<BoundingBox> boxes = BuilderBounds.buildVolumes(3, DIMS);
        int trainStart = BuilderWorldLayout.trainStartX(3, DIMS);
        assertTrue(boxes.get(0).minX() > trainStart,
                "the first carriage starts after the back pad");
        assertFalse(BuilderBounds.isInsideBuild(
                new BlockPos(trainStart, BuilderWorldLayout.TRAIN_Y, 0), boxes));
    }

    @Test
    @DisplayName("A single carriage produces a single box with no pad offset")
    void oneCarriageProducesOneBox() {
        List<BoundingBox> boxes = BuilderBounds.buildVolumes(1, DIMS);
        assertEquals(1, boxes.size());
        assertEquals(BuilderWorldLayout.trainStartX(1, DIMS), boxes.get(0).minX());
    }

    @Test
    @DisplayName("No carriages means no bounds — nothing is ever in the build")
    void zeroCarriagesProduceNoBoxes() {
        assertTrue(BuilderBounds.buildVolumes(0, DIMS).isEmpty());
        assertFalse(BuilderBounds.isInsideBuild(BlockPos.ZERO, List.of()));
    }

    @Test
    @DisplayName("Inside a carriage is in bounds; the roof, the far end and the platform are not")
    void containmentEdges() {
        List<BoundingBox> boxes = BuilderBounds.buildVolumes(3, DIMS);
        BoundingBox first = boxes.get(0);

        assertTrue(BuilderBounds.isInsideBuild(
                new BlockPos(first.minX() + 1, BuilderWorldLayout.TRAIN_Y + 1, 1), boxes));
        assertTrue(BuilderBounds.isInsideBuild(
                new BlockPos(first.maxX(), first.maxY(), first.maxZ()), boxes),
                "the far corner is still inside");

        assertFalse(BuilderBounds.isInsideBuild(
                new BlockPos(first.minX(), first.maxY() + 1, 0), boxes), "one block above the roof");
        assertFalse(BuilderBounds.isInsideBuild(
                new BlockPos(boxes.get(2).maxX() + 1, BuilderWorldLayout.TRAIN_Y, 0), boxes),
                "one block past the last carriage");
        assertFalse(BuilderBounds.isInsideBuild(
                new BlockPos(0, BuilderWorldLayout.Y_STAND, 40), boxes), "out on the platform");
        assertFalse(BuilderBounds.isInsideBuild(
                new BlockPos(first.minX(), BuilderWorldLayout.TRAIN_Y, DIMS.width()), boxes),
                "one block wide of the carriage");
    }

    @Test
    @DisplayName("Volumes start at the train floor, not below it")
    void volumesSitOnTheTrackNotInIt() {
        BoundingBox first = BuilderBounds.buildVolumes(1, DIMS).get(0);
        assertEquals(BuilderWorldLayout.TRAIN_Y, first.minY());
        assertFalse(BuilderBounds.isInsideBuild(
                new BlockPos(first.minX(), BuilderWorldLayout.Y_TRACK_RAIL, 0),
                List.of(first)), "the rails are below the build");
    }
}
