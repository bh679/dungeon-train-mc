package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.skybox.SkyboxSectionRebuild;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import net.minecraft.client.Minecraft;

/**
 * Client mirror of {@link games.brennan.dungeontrain.portal.PortalTestSession} — whether this player
 * is inside a test dimensional carriage right now, and which room it was stamped from.
 *
 * <p>Read by {@code EditorMenuScreen} to decide whether to offer the Back row. Same shape as
 * {@link DebugFlagsState} and the other server-state mirrors: a static the packet writes and the
 * menu reads, with a safe default for a client that has been told nothing.</p>
 */
public final class PortalTestSessionState {

    private static boolean active = false;
    private static String roomName = "";
    private static boolean reseed = false;
    /** The test settings rows: the author's picks ("" / -1 for Auto) and what Auto stands for. */
    private static String stage = "";
    private static int level = -1;
    private static int phase = -1;
    private static int autoLevel = 0;
    private static java.util.List<String> stageIds = java.util.List.of();
    private static java.util.List<String> stageNames = java.util.List.of();

    private PortalTestSessionState() {}

    public static void update(PortalTestSessionPacket packet) {
        boolean was = active;
        active = packet.active();
        roomName = packet.roomName();
        reseed = packet.reseed();
        stage = packet.stage();
        level = packet.level();
        phase = packet.phase();
        autoLevel = packet.autoLevel();
        stageIds = packet.stageIds();
        stageNames = packet.stageNames();
        // A test puts Skybox Blocks back however the author's switch is set, and that changes what
        // they cull as well as what they draw. The meshes standing when it starts or ends were
        // built against the other answer, so they are rebuilt — but only when the switch is off,
        // which is the only case where the two answers differ. Just the sections that hold one:
        // a full allChanged() here is what froze a one-worker Sodium client on the way out of a
        // test, landing on top of the teleport home and the sweep of the test window.
        if (was != active && !ClientDisplayConfig.areSkyboxBlocksOn()) {
            SkyboxSectionRebuild.rebuildAround(Minecraft.getInstance());
        }
    }

    /** True while this player is standing in a stamped test carriage. */
    public static boolean active() { return active; }

    /** The room it was stamped from, for the row's label. Empty when nothing is active. */
    public static String roomName() { return roomName; }

    /** The world's reseed-on-test switch, for the editor's toggle beside Test the Carriage. */
    public static boolean reseed() { return reseed; }

    /** The stage the author picked for tests, or "" for Auto. */
    public static String stage() { return stage; }

    /** The Diff-Level the author picked for tests, or -1 for Auto. */
    public static int level() { return level; }

    /** The band ({@code TrainPhase} ordinal) the author picked for tests, or -1 for Auto. */
    public static int phase() { return phase; }

    /** The Diff-Level a test stands at on Auto. */
    public static int autoLevel() { return autoLevel; }

    /** The stages there are to pick from, lowest level first, and their names alongside. */
    public static java.util.List<String> stageIds() { return stageIds; }
    public static java.util.List<String> stageNames() { return stageNames; }
}
