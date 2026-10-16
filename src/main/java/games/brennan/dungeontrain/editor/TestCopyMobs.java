package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.command.PortalTestCommand;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTestSession;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The editor's <b>Test Mobs</b> setting (Settings → Test Mobs | Frozen | Live), default Frozen.
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

    /** True when the Test Mobs setting is Live. */
    public static boolean isLive(ServerLevel level) {
        return DungeonTrainWorldData.get(level.getServer().overworld()).isTestMobsLive();
    }

    /**
     * True when {@code pos} lies inside a running Test-the-Carriage copy. Every test stamps into the
     * overworld, and each session is registered before its copy is stamped, so the copy's very first
     * mobs already read as inside it.
     */
    public static boolean isInTestCopy(ServerLevel level, BlockPos pos) {
        ServerLevel overworld = level.getServer().overworld();
        if (level != overworld) return false;
        for (CarriageTestSession.Session session : CarriageTestSession.all()) {
            if (session.box().isInside(pos)) return true;
        }
        if (!PortalTestSession.anyActive()) return false;
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        for (var entry : PortalTestSession.entries()) {
            if (PortalTestCommand.windowBox(overworld, entry.getValue().structure(), dims).isInside(pos)) {
                return true;
            }
        }
        return false;
    }
}
