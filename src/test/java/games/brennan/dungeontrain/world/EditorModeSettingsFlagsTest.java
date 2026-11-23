package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.editor.EditorModeSetting;
import games.brennan.dungeontrain.editor.EditorModeSettings;
import games.brennan.dungeontrain.editor.EditorSettingMode;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.editor.EditorSettingMode.EDITOR;
import static games.brennan.dungeontrain.editor.EditorSettingMode.TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's per-mode Settings ({@link EditorModeSetting}), kept with the world: each has an
 * editing and a test value, a world nobody touched writes none of them, a change survives a reload,
 * and changing one mode never moves the other.
 */
final class EditorModeSettingsFlagsTest {

    private static DungeonTrainWorldData roundTrip(DungeonTrainWorldData data) {
        return DungeonTrainWorldData.load(data.save(new CompoundTag(), null));
    }

    private static void set(DungeonTrainWorldData data, EditorSettingMode mode, EditorModeSetting s, boolean v) {
        data.setEditorModeSettings(data.editorModeSettings().with(mode, s, v));
    }

    @Test
    @DisplayName("defaults: everything On, except Fluid flow Off while editing and Mobs Blocks in both")
    void defaults() {
        EditorModeSettings d = DungeonTrainWorldData.createDefault().editorModeSettings();
        for (EditorSettingMode mode : EditorSettingMode.values()) {
            assertTrue(d.get(mode, EditorModeSetting.OBSERVERS));
            assertTrue(d.get(mode, EditorModeSetting.REDSTONE));
            assertTrue(d.get(mode, EditorModeSetting.TNT));
            assertTrue(d.get(mode, EditorModeSetting.AUTO_FLY));
            assertFalse(d.get(mode, EditorModeSetting.MOBS_LIVE));
        }
        assertFalse(d.get(EDITOR, EditorModeSetting.FLUID_FLOW));
        assertTrue(d.get(TEST, EditorModeSetting.FLUID_FLOW));
    }

    @Test
    @DisplayName("an untouched world writes no setting")
    void defaultsUnwritten() {
        CompoundTag tag = DungeonTrainWorldData.createDefault().save(new CompoundTag(), null);
        for (EditorModeSetting s : EditorModeSetting.values()) {
            for (EditorSettingMode mode : EditorSettingMode.values()) {
                assertFalse(tag.contains(s.tag(mode)), s.tag(mode));
            }
        }
    }

    @Test
    @DisplayName("every setting, in each mode, survives a reload without moving anything else")
    void eachValueRoundTripsAlone() {
        for (EditorModeSetting s : EditorModeSetting.values()) {
            for (EditorSettingMode mode : EditorSettingMode.values()) {
                DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
                boolean flipped = !s.defaultValue(mode);
                set(data, mode, s, flipped);
                EditorModeSettings back = roundTrip(data).editorModeSettings();
                assertEquals(EditorModeSettings.DEFAULTS.with(mode, s, flipped), back, s + " " + mode);
            }
        }
    }

    @Test
    @DisplayName("Redstone Off in test leaves editing On, and the other way round")
    void modesDoNotMix() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        set(data, TEST, EditorModeSetting.REDSTONE, false);
        assertTrue(data.editorModeSettings().get(EDITOR, EditorModeSetting.REDSTONE));
        assertFalse(data.editorModeSettings().get(TEST, EditorModeSetting.REDSTONE));

        set(data, EDITOR, EditorModeSetting.REDSTONE, false);
        set(data, TEST, EditorModeSetting.REDSTONE, true);
        assertFalse(data.editorModeSettings().get(EDITOR, EditorModeSetting.REDSTONE));
        assertTrue(data.editorModeSettings().get(TEST, EditorModeSetting.REDSTONE));
        assertFalse(data.editorModeSettings().onInBoth(EditorModeSetting.REDSTONE));
    }

    @Test
    @DisplayName("worlds saved before the split keep their values: old editor tags, and testMobsLive as Mobs in test")
    void legacyTagsLoad() {
        CompoundTag old = DungeonTrainWorldData.createDefault().save(new CompoundTag(), null);
        old.putBoolean("editorRedstoneOn", false);
        old.putBoolean("editorTntOn", false);
        old.putBoolean("editorObserversOn", false);
        old.putBoolean("editorAutoFlyOn", false);
        old.putBoolean("editorMobsLive", true);
        old.putBoolean("testMobsLive", true);
        EditorModeSettings s = DungeonTrainWorldData.load(old).editorModeSettings();
        assertFalse(s.get(EDITOR, EditorModeSetting.REDSTONE));
        assertFalse(s.get(EDITOR, EditorModeSetting.TNT));
        assertFalse(s.get(EDITOR, EditorModeSetting.OBSERVERS));
        assertFalse(s.get(EDITOR, EditorModeSetting.AUTO_FLY));
        assertTrue(s.get(EDITOR, EditorModeSetting.MOBS_LIVE));
        assertTrue(s.get(TEST, EditorModeSetting.MOBS_LIVE));
        // Test copies start from their own defaults rather than inheriting the editing Off.
        assertTrue(s.get(TEST, EditorModeSetting.REDSTONE));
    }

    @Test
    @DisplayName("switching back to the default clears it from the save")
    void backToDefaultIsUnwritten() {
        DungeonTrainWorldData data = DungeonTrainWorldData.createDefault();
        set(data, EDITOR, EditorModeSetting.FLUID_FLOW, true);
        set(data, EDITOR, EditorModeSetting.FLUID_FLOW, false);
        assertFalse(data.save(new CompoundTag(), null).contains("editorFluidFlowOn"));
    }
}
