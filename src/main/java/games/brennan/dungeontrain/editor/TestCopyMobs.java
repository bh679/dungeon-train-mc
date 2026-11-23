package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The test-mode value of the editor's <b>Mobs</b> setting (Settings → Mobs | Blocks | Live while
 * standing in a test), default Blocks — what used to be its own Test Mobs row.
 *
 * <p>Test the Carriage stamps its copies into the Train Editor world's basement, and the whole of
 * that world is editor space to {@link FrozenMobs#isEditorSpace} — so a mob the author placed frozen
 * (Mobs | Blocks) used to stand in the test copy as the same statue it is in the plot. Live thaws
 * those mobs inside a running test copy, for every template type a test can stand up: carriages,
 * contents, whole rooms and groups, track pieces and dimensional carriages. The author stays in
 * Creative, so hostiles wander but do not target them.</p>
 *
 * <p>Scoped by <b>position</b>, not by "a test is running": an editor plot re-stamped while a test
 * is open keeps its statues. Read at spawn time, so a change applies from the next stamp — the next
 * test, or Reseed inside one.</p>
 */
public final class TestCopyMobs {

    private TestCopyMobs() {}

    /**
     * Whether a frozen mob's NBT should be thawed: always outside editor space, and inside it only
     * when the setting is Live and the spawn lands in a test copy.
     */
    public static boolean shouldThaw(boolean editorSpace, boolean testMobsLive, boolean inTestCopy) {
        return !editorSpace || (testMobsLive && inTestCopy);
    }

    /** True when Mobs is Live in test mode — the old Test Mobs setting. */
    public static boolean isLive(ServerLevel level) {
        return EditorModeSettingsService.current().get(EditorSettingMode.TEST, EditorModeSetting.MOBS_LIVE);
    }

    /** True when {@code pos} lies inside a running Test-the-Carriage copy; see {@link EditorSettingMode#isInTestCopy}. */
    public static boolean isInTestCopy(ServerLevel level, BlockPos pos) {
        return EditorSettingMode.isInTestCopy(level, pos);
    }
}
