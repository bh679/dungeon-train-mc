package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.command.PortalTestCommand;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTestSession;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which of an {@link EditorModeSetting}'s two values applies: <b>EDITOR</b> while authoring in a
 * plot, <b>TEST</b> inside a running Test-the-Carriage copy.
 *
 * <p>Test copies are stamped into the Train Editor world's basement, which every setting's gate
 * already counts as editor space; this is what tells the two apart. Blocks and entities ask by
 * position ({@link #at}), so a plot re-stamped while a test is open keeps the editing values; the
 * player-facing settings (Auto fly, the Settings rows, a command with no scope) ask by player
 * ({@link #of}).</p>
 */
public enum EditorSettingMode {
    EDITOR,
    TEST;

    /** The command word: {@code editor} or {@code test}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@link #id} back to a mode, or null for anything else. */
    public static @Nullable EditorSettingMode parse(String id) {
        for (EditorSettingMode mode : values()) {
            if (mode.id().equals(id)) return mode;
        }
        return null;
    }

    /** TEST while {@code pos} lies inside a running test copy, else EDITOR. */
    public static EditorSettingMode at(ServerLevel level, BlockPos pos) {
        return isInTestCopy(level, pos) ? TEST : EDITOR;
    }

    /** TEST while {@code player} is standing in a test they started, else EDITOR. */
    public static EditorSettingMode of(ServerPlayer player) {
        return CarriageTestSession.has(player.getUUID()) || PortalTestSession.has(player.getUUID())
            ? TEST : EDITOR;
    }

    /**
     * True when {@code pos} lies inside a running Test-the-Carriage copy. Every test stamps into the
     * overworld, and each session is registered before its copy is stamped, so the copy's very first
     * blocks and mobs already read as inside it. No test running: two empty-checks.
     */
    public static boolean isInTestCopy(ServerLevel level, BlockPos pos) {
        boolean carriageTests = CarriageTestSession.anyActive();
        boolean portalTests = PortalTestSession.anyActive();
        if (!carriageTests && !portalTests) return false;
        ServerLevel overworld = level.getServer().overworld();
        if (level != overworld) return false;
        if (carriageTests) {
            for (CarriageTestSession.Session session : CarriageTestSession.all()) {
                if (session.box().isInside(pos)) return true;
            }
        }
        if (!portalTests) return false;
        for (BoundingBox box : portalWindows(overworld)) {
            if (box.isInside(pos)) return true;
        }
        return false;
    }

    /** The portal-test windows last worked out, and the sessions they were worked out from. */
    private record Windows(List<PortalTestSession.Session> sessions, List<BoundingBox> boxes) {}

    private static volatile Windows windows = new Windows(List.of(), List.of());

    /**
     * Each running portal test's window box. Worked out again only when a session starts, ends or
     * re-tiles (each of which replaces the session record), not on every ask: this runs on every
     * fluid spread and signal read inside the editor world while a test is open, and a window is
     * not just arithmetic ({@code PortalTestCommand.windowBox} reads the footprint and layout).
     */
    private static List<BoundingBox> portalWindows(ServerLevel overworld) {
        List<PortalTestSession.Session> now = new ArrayList<>();
        for (var entry : PortalTestSession.entries()) now.add(entry.getValue());
        Windows cached = windows;
        if (sameSessions(cached.sessions(), now)) return cached.boxes();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        List<BoundingBox> boxes = new ArrayList<>(now.size());
        for (PortalTestSession.Session session : now) {
            boxes.add(PortalTestCommand.windowBox(overworld, session.structure(), dims));
        }
        windows = new Windows(List.copyOf(now), List.copyOf(boxes));
        return boxes;
    }

    /** Same sessions, by identity, in the same order — a re-tiled session is a new record. */
    private static boolean sameSessions(List<PortalTestSession.Session> a, List<PortalTestSession.Session> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }
}
