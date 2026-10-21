package games.brennan.dungeontrain.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's Redstone and TNT settings, kept with the world: On by default, so a world saved
 * before they existed (or one nobody touched) changes nothing, and an Off survives a reload.
 */
final class EditorRedstoneTntFlagsTest {

    private static DungeonTrainWorldData roundTrip(DungeonTrainWorldData data) {
        return DungeonTrainWorldData.load(data.save(new CompoundTag(), null));
    }

    @Test
    @DisplayName("both settings start On and are not written while On")
    void defaultsOnAndUnwritten() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        assertTrue(data.isEditorRedstoneOn());
        assertTrue(data.isEditorTntOn());
        CompoundTag tag = data.save(new CompoundTag(), null);
        assertFalse(tag.contains("editorRedstoneOn"));
        assertFalse(tag.contains("editorTntOn"));
    }

    @Test
    @DisplayName("Off survives a reload, each setting on its own")
    void offSurvivesTheRoundTrip() {
        DungeonTrainWorldData redstoneOff = DungeonTrainWorldData.createDefault();
        redstoneOff.setEditorRedstoneOn(false);
        DungeonTrainWorldData back = roundTrip(redstoneOff);
        assertFalse(back.isEditorRedstoneOn());
        assertTrue(back.isEditorTntOn());

        DungeonTrainWorldData tntOff = DungeonTrainWorldData.createDefault();
        tntOff.setEditorTntOn(false);
        back = roundTrip(tntOff);
        assertTrue(back.isEditorRedstoneOn());
        assertFalse(back.isEditorTntOn());
    }

    @Test
    @DisplayName("switching back On clears it from the save")
    void backOnIsDefaultAgain() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        data.setEditorRedstoneOn(false);
        data.setEditorRedstoneOn(true);
        assertTrue(roundTrip(data).isEditorRedstoneOn());
        assertFalse(data.save(new CompoundTag(), null).contains("editorRedstoneOn"));
    }
}
