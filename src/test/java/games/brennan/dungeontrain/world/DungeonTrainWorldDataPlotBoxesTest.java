package games.brennan.dungeontrain.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where each portal-room plot was last stamped, kept with the world.
 *
 * <p>Round-tripped through the tag as {@code DungeonTrainWorldDataOffsetTest} does. What matters is
 * that a box recorded before a save is the box a clear after a reload erases — the whole point of
 * recording it is outliving the session that stamped it.</p>
 */
final class DungeonTrainWorldDataPlotBoxesTest {

    private static DungeonTrainWorldData roundTrip(DungeonTrainWorldData data) {
        return DungeonTrainWorldData.load(data.save(new CompoundTag(), null));
    }

    @Test
    @DisplayName("a recorded box comes back after a reload, exactly")
    void recordedBoxSurvivesTheRoundTrip() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.recordPortalPlotBox("house", 33, 230, 400, 11, 7, 11);
        data.recordPortalPlotBox("miniword", 49, 230, 400, 48, 30, 48);

        DungeonTrainWorldData back = roundTrip(data);
        assertArrayEquals(new int[] {33, 230, 400, 11, 7, 11}, back.portalPlotBox("house"));
        assertArrayEquals(new int[] {49, 230, 400, 48, 30, 48}, back.portalPlotBox("miniword"));
    }

    @Test
    @DisplayName("a forgotten box is gone after a reload too")
    void forgottenBoxStaysForgotten() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.recordPortalPlotBox("house", 33, 230, 400, 11, 7, 11);
        data.forgetPortalPlotBox("house");
        assertNull(roundTrip(data).portalPlotBox("house"));
    }

    @Test
    @DisplayName("a world saved before boxes were recorded loads with none — the predicted layout still clears it")
    void legacyWorldHasNoBoxes() {
        CompoundTag legacy = DungeonTrainWorldData.createDefault().save(new CompoundTag(), null);
        legacy.remove("editorPortalPlotBoxes");
        assertTrue(DungeonTrainWorldData.load(legacy).portalPlotBoxes().isEmpty());
    }

    @Test
    @DisplayName("the copy handed out cannot change what is recorded")
    void copiesAreCopies() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.recordPortalPlotBox("house", 33, 230, 400, 11, 7, 11);
        data.portalPlotBox("house")[0] = 999;
        data.portalPlotBoxes().clear();
        assertArrayEquals(new int[] {33, 230, 400, 11, 7, 11}, data.portalPlotBox("house"));
    }
}
