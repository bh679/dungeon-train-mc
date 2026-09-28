package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorUnsavedRequestPacket;

import java.util.List;

/**
 * Save prompt in front of Test the Carriage — {@code /dungeontrain portal test} for a dimensional
 * carriage, {@code /dungeontrain editor test} for a carriage or contents template.
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

    /** The command the whole screen exists to gate, for a dimensional carriage. */
    private static final String TEST_COMMAND = "dungeontrain portal test";

    /** The same for a carriage or contents template — {@code CarriageTestCommand}. */
    private static final String TEMPLATE_TEST_COMMAND = "dungeontrain editor test";

    /**
     * Position-resolved save, the same one the File tab's Save row runs. The player is standing in
     * the plot by construction — this screen is only reachable from the menu shown inside it — so
     * the {@code save model …} + teleport chain {@link UnsavedCheckScreen} needs doesn't apply.
     */
    private static final String SAVE_COMMAND = "dungeontrain save";

    /** The editor category and model key {@link EditorDirtyCheck} publishes for this template. */
    private final String categoryId;
    private final String dirtyKey;
    /** The test command, naming the template — the author need not be standing in it. */
    private final String testCommand;
    private final String saveCommand;
    private boolean requestSent = false;
    private boolean bypassDispatched = false;

    /** Test a dimensional carriage. */
    public PortalTestSaveCheckScreen(String roomName) {
        this("portals", roomName == null || roomName.isEmpty() ? "" : dirtyKey(roomName),
            roomName == null || roomName.isEmpty() ? TEST_COMMAND : TEST_COMMAND + " " + roomName);
    }

    private PortalTestSaveCheckScreen(String categoryId, String dirtyKey, String testCommand) {
        this(categoryId, dirtyKey, testCommand, SAVE_COMMAND);
    }

    private PortalTestSaveCheckScreen(String categoryId, String dirtyKey, String testCommand, String saveCommand) {
        this.categoryId = categoryId;
        this.dirtyKey = dirtyKey;
        this.testCommand = testCommand;
        this.saveCommand = saveCommand;
    }

    /**
     * Test a chunk frame: the server picks a chunk dimension it dresses and shows the frame on it.
     * Saved by name, so it works from a tile selected anywhere in the browser.
     */
    public static PortalTestSaveCheckScreen forFrame(String frame) {
        return new PortalTestSaveCheckScreen("chunk_frames", "chunk_frame." + frame,
            TEST_COMMAND + " frame " + frame, "dungeontrain editor chunkframe save " + frame);
    }

    /**
     * Test a carriage or contents template. The category id is the editor's ({@code carriages} /
     * {@code contents}), which is also the command literal and the dirty scan's category, and both
     * scans key a template's row on its bare id.
     */
    public static PortalTestSaveCheckScreen forTemplate(String categoryId, String id) {
        return new PortalTestSaveCheckScreen(categoryId, id,
            TEMPLATE_TEST_COMMAND + " " + categoryId + " " + id);
    }

    /** The command this screen dispatches — visible for testing. */
    public String testCommand() { return testCommand; }

    @Override public String title() { return MenuLang.t("portal_test.title"); }

    @Override public List<CommandMenuEntry> entries() {
        if (!requestSent) {
            // Drop any list left over from an earlier check so a stale row can't answer for this one.
            EditorStatusHudOverlay.clearUnsavedList();
            DungeonTrainNet.sendToServer(new EditorUnsavedRequestPacket());
            requestSent = true;
            return List.of(new CommandMenuEntry.Loading(MenuLang.t("unsaved.checking")));
        }

        List<EditorDirtyCheck.DirtyEntry> rows = EditorStatusHudOverlay.unsavedList();
        if (rows == null) {
            // Server hasn't replied yet.
            return List.of(new CommandMenuEntry.Loading(MenuLang.t("unsaved.checking")));
        }

        if (!isDirty(rows, categoryId, dirtyKey)) {
            // Clean: go straight in. Dispatching from inside entries() is safe — CommandRunner
            // posts to the chat queue — and the guard keeps it to one dispatch per screen.
            if (!bypassDispatched) {
                bypassDispatched = true;
                CommandRunner.run(testCommand);
                CommandMenuState.close();
            }
            return List.of(new CommandMenuEntry.Loading(MenuLang.t("portal_test.testing")));
        }

        return List.of(
            new CommandMenuEntry.ClientAction(MenuLang.t("portal_test.save_and_test"), () -> {
                CommandRunner.run(saveCommand);
                CommandRunner.run(testCommand);
                CommandMenuState.close();
            }, true),
            new CommandMenuEntry.ClientAction(MenuLang.t("portal_test.test_without_saving"), () -> {
                CommandRunner.run(testCommand);
                CommandMenuState.close();
            }),
            new CommandMenuEntry.Back(MenuLang.t("common.back")));
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
        if (roomName == null || roomName.isEmpty()) return false;
        return isDirty(rows, "portals", dirtyKey(roomName));
    }

    /** True iff {@code rows} carries an unsaved row for {@code (categoryId, key)}. */
    static boolean isDirty(List<EditorDirtyCheck.DirtyEntry> rows, String categoryId, String key) {
        if (rows == null || key == null || key.isEmpty()) return false;
        for (EditorDirtyCheck.DirtyEntry r : rows) {
            if (r.isUnsaved() && categoryId.equals(r.categoryId()) && key.equals(r.modelId())) {
                return true;
            }
        }
        return false;
    }
}
