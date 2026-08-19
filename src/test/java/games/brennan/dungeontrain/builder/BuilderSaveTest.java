package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a builder save treats as "the build".
 *
 * <p>Train Outside parks a whole run and every carriage in it is editable, so the build is the run —
 * saved as one carriage-group template rather than one template per carriage. The gate that keeps that
 * honest is the footprint: a group's carriage count IS its length, so a template cannot claim to be a
 * size it isn't, and a world whose train is a different length refuses it rather than stamping it
 * short.</p>
 */
final class BuilderSaveTest {

    /** The shipped default: 9 long, 7 high, 7 wide. */
    private static final CarriageDims DIMS = new CarriageDims(9, 7, 7);

    @Test
    @DisplayName("A run of carriages is recognised by its length")
    void wholeRunsAreCounted() {
        assertEquals(1, CarriageGroupTemplateStore.carriageCount(new Vec3i(9, 7, 7), DIMS));
        assertEquals(3, CarriageGroupTemplateStore.carriageCount(new Vec3i(27, 7, 7), DIMS));
        assertEquals(16, CarriageGroupTemplateStore.carriageCount(new Vec3i(144, 7, 7), DIMS));
    }

    @Test
    @DisplayName("A length that isn't a whole number of carriages is not a group")
    void partialRunsAreRefused() {
        // One block short of two carriages is not "nearly two" — it is a template that would be
        // stamped into the wrong space.
        assertEquals(0, CarriageGroupTemplateStore.carriageCount(new Vec3i(17, 7, 7), DIMS));
        assertEquals(0, CarriageGroupTemplateStore.carriageCount(new Vec3i(0, 7, 7), DIMS));
    }

    @Test
    @DisplayName("A group authored at other carriage dimensions is refused outright")
    void otherDimensionsAreRefused() {
        // Height and width must match exactly: a taller carriage is a different carriage, and its
        // group cannot be laid into this train at all.
        assertEquals(0, CarriageGroupTemplateStore.carriageCount(new Vec3i(27, 8, 7), DIMS));
        assertEquals(0, CarriageGroupTemplateStore.carriageCount(new Vec3i(27, 7, 5), DIMS));
    }

    @Test
    @DisplayName("Nonsense in, zero out — never a guess")
    void nullsAreNotGroups() {
        assertEquals(0, CarriageGroupTemplateStore.carriageCount(null, DIMS));
        assertEquals(0, CarriageGroupTemplateStore.carriageCount(new Vec3i(27, 7, 7), null));
    }
}
