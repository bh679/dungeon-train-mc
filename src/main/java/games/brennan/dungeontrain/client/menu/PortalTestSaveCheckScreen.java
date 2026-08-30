package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorUnsavedRequestPacket;

import java.util.List;

/**
 * Save prompt in front of {@code /dungeontrain portal test} — the Nav tab's "Test the Carriage".
 *
 * <p>The test stamps the room from the <b>saved</b> template
 * ({@code PortalCarriageBuilder.stampPairStructure} reads
 * {@link games.brennan.dungeontrain.editor.PortalRoomTemplateStore}), not from the live plot. So an
 * author who has been building and hits Test walks around their last save with nothing to tell them
 * the change they just made isn't in front of them. This screen asks first.</p>
 *
 * <p><b>One room, not the roster.</b> The dirty scan covers every category; this screen reads only
 * the row for the plot the player is standing in — matched on {@code portals} /
 * {@code portal_room.<name>}, the keys {@link EditorDirtyCheck} publishes. Both ways a room can be
 * dirty are already covered there: block edits against the post-stamp snapshot, and a resize that
 * no save has made permanent.</p>
 *
 * <p>A clean room never sees the screen — the same bypass {@link UnsavedCheckScreen} does, so Test
 * stays one click when there is nothing to answer.</p>
 */
public final class PortalTestSaveCheckScreen implements MenuScreen {

    /** The command the whole screen exists to gate. */
    private static final String TEST_COMMAND = "dungeontrain portal test";

    /**
     * Position-resolved save, the same one the File tab's Save row runs. The player is standing in
     * the plot by construction — this screen is only reachable from the menu shown inside it — so
     * the {@code save model …} + teleport chain {@link UnsavedCheckScreen} needs doesn't apply.
     */
    private static final String SAVE_COMMAND = "dungeontrain save";

    private final String roomName;
    private boolean requestSent = false;
    private boolean bypassDispatched = false;

    public PortalTestSaveCheckScreen(String roomName) {
        this.roomName = roomName == null ? "" : roomName;
    }

    @Override public String title() { return "Save before test?"; }

    @Override public List<CommandMenuEntry> entries() {
        if (!requestSent) {
            // Drop any list left over from an earlier check so a stale row can't answer for this one.
            EditorStatusHudOverlay.clearUnsavedList();
            DungeonTrainNet.sendToServer(new EditorUnsavedRequestPacket());
            requestSent = true;
            return List.of(new CommandMenuEntry.Loading("Checking..."));
        }

        List<EditorDirtyCheck.DirtyEntry> rows = EditorStatusHudOverlay.unsavedList();
        if (rows == null) {
            // Server hasn't replied yet.
            return List.of(new CommandMenuEntry.Loading("Checking..."));
        }

        if (!isDirty(rows, roomName)) {
            // Clean: go straight in. Dispatching from inside entries() is safe — CommandRunner
            // posts to the chat queue — and the guard keeps it to one dispatch per screen.
            if (!bypassDispatched) {
                bypassDispatched = true;
                CommandRunner.run(TEST_COMMAND);
                CommandMenuState.close();
            }
            return List.of(new CommandMenuEntry.Loading("Testing..."));
        }

        return List.of(
            new CommandMenuEntry.ClientAction("Save and test", () -> {
                CommandRunner.run(SAVE_COMMAND);
                CommandRunner.run(TEST_COMMAND);
                CommandMenuState.close();
            }, true),
            new CommandMenuEntry.ClientAction("Test without saving", () -> {
                CommandRunner.run(TEST_COMMAND);
                CommandMenuState.close();
            }),
            new CommandMenuEntry.Back("< Back"));
    }

    /**
     * The {@link EditorDirtyCheck.DirtyEntry#modelId()} key the portal-room scan publishes for
     * {@code roomName}. Kept here as one expression so the match below can't drift from the scan
     * silently — a mismatch would read as "always clean" and never prompt.
     */
    static String dirtyKey(String roomName) {
        return "portal_room." + roomName;
    }

    /** True iff {@code rows} carries an unsaved row for this room's plot. */
    static boolean isDirty(List<EditorDirtyCheck.DirtyEntry> rows, String roomName) {
        if (rows == null || roomName == null || roomName.isEmpty()) return false;
        String key = dirtyKey(roomName);
        for (EditorDirtyCheck.DirtyEntry r : rows) {
            if (r.isUnsaved() && "portals".equals(r.categoryId()) && key.equals(r.modelId())) {
                return true;
            }
        }
        return false;
    }
}
