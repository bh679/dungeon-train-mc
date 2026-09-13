package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant;
import games.brennan.dungeontrain.portal.PortalRoomSettings;

/**
 * The rows that only a portal-room plot shows — its box, what its walls do, and what is
 * found inside it.
 *
 * <p>Extracted from {@link EditorMenuScreen}, which had reached the 800-line ceiling in the
 * coding-style rule before the tab split added to it. These eleven builders are one cohesive
 * group (every one of them is dead unless the player is standing in a portal room) and so
 * are the natural seam. Bodies and signatures are unchanged by the move.</p>
 *
 * <p>Every command here is <b>position-resolved</b> — the server reads which plot the player
 * is standing in rather than taking a model id — so nothing spliced into these strings can go
 * stale.</p>
 */
public final class EditorMenuPortalRows {

    private EditorMenuPortalRows() {}

    /** The command root every row sends to for the room the author is standing in. */
    public static final String STOOD_IN_PREFIX = "dungeontrain editor portals";

    /**
     * The command root for a named room — what the editor screen sends for a room the author is
     * previewing rather than standing in. The server hangs the same settings tree off both roots
     * ({@code EditorCommand.portalRoomSettingNodes}), so a row built with either prefix reaches the
     * same handler.
     */
    public static String prefixFor(String roomName) {
        return STOOD_IN_PREFIX + " room " + roomName;
    }

    /**
     * The row that says what a portal room does at its walls, or null outside a portal room plot.
     *
     * <p>One cycling button rather than a stepper or a drilldown: there are three modes, so any of
     * them is at most two taps away, and staying open lets the player tap past the one they do not
     * want.</p>
     */
    public static CommandMenuEntry wallsModeRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.modeLabel(currentMode),
            prefix + " mode next");
    }

    /**
     * The Lock row, or null unless the walls seal — the only case where there is a shell for the
     * block to describe.
     *
     * <p>A picker, not a cycle, for the reason {@link #copiesBlockRowFor} is one: the value is any
     * block in the registry, so tapping the row takes whatever the author is holding. An empty hand
     * means no shell at all. No Edit half — a seal of mixed blocks is not what a seal is for.</p>
     */
    public static CommandMenuEntry lockRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        if (!EditorPlotLabelsRenderer.hasLockRowFor(currentMode)) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.lockLabel(currentMode) + "  (+ held)",
            prefix + " lock held");
    }

    /**
     * The Copies row, or null unless the walls are set to one of the two endless modes — the only
     * ones that append tiles for the setting to describe.
     */
    public static CommandMenuEntry copiesRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        if (!PortalRoomSettings.parse(currentMode).copiesApply()) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.copiesLabel(currentMode),
            prefix + " copies next");
    }

    /**
     * One plane's Block row, or null unless Copies is set to Single — the one value that reads a
     * block. The Floor and Roof rows are this same shape, aimed at their own plane.
     *
     * <p>A picker, not a cycle: the value is any block in the registry, so tapping the row takes
     * whatever the author is holding rather than stepping to a "next" nobody could enumerate. The
     * menu is opened by a key toggle rather than by holding a tool, so their main hand is free for
     * the block itself. <b>Edit</b> opens the Block Variant menu on that plane, which is how one
     * block becomes a variant of several.</p>
     */
    public static CommandMenuEntry copiesBlockRowFor(String currentMode, PortalRoomCopiesVariant.Plane plane, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        if (!EditorPlotLabelsRenderer.hasCopiesBlockRowFor(currentMode)) return null;
        // Value on the left, a way into its editor on the right — the same Split the Books row uses.
        return new CommandMenuEntry.Split(
            new CommandMenuEntry.Stay(EditorPlotLabelsRenderer.copiesBlockLabel(plane),
                prefix + " copies " + plane.id() + " held"),
            new CommandMenuEntry.Stay("Edit",
                prefix + " copies " + plane.id() + " edit"),
            0.72);
    }

    /**
     * The Door Wall row, or null unless the walls are set to Endless Repetition — the one mode whose
     * appended tiles carry a wall of their own.
     *
     * <p>Sits directly under Copies, beside which it belongs: both describe what an appended tile is
     * made of. Off by default, so a room that has never been given the setting shows "Sealed" and
     * behaves exactly as it always did.</p>
     */
    public static CommandMenuEntry doorWallRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        if (!EditorPlotLabelsRenderer.hasDoorWallRow(currentMode)) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.doorWallLabel(currentMode),
            prefix + " doorwall next");
    }

    /**
     * The Contents row — whether the room is furnished from the ordinary contents pool, and how a
     * furnishing smaller than the room is fitted into it.
     *
     * <p>Shown for every portal room, unlike Copies: furnishing is not a property of the walls, so a
     * sealed room can take one as readily as a repeating one.</p>
     */
    public static CommandMenuEntry roomContentsRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.roomContentsLabel(currentMode),
            prefix + " contents next");
    }

    /**
     * The Books row — whether every book found in the room is by one author, and how that author is
     * chosen.
     *
     * <p>Shown for every portal room, on the same reasoning as Contents, and deliberately NOT gated
     * on the room being furnished: a room can hold books without drawing a contents template, since
     * its own template may have shelves stamped into it.</p>
     */
    public static CommandMenuEntry roomBooksRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        CommandMenuEntry cycle = new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.roomBooksLabel(currentMode),
            prefix + " books next");
        // Off has no dials, so the row is the value alone. Once the room stocks an author the Edit
        // button rides beside it rather than taking a row of its own — the weights and the author
        // band belong to the value next to them.
        if (!PortalRoomSettings.parse(currentMode).books().weightsApply()) {
            return cycle;
        }
        return new CommandMenuEntry.Split(cycle,
            new CommandMenuEntry.DrillIn("Edit", new PortalRoomBooksScreen(currentMode, prefix)),
            0.72);
    }

    /**
     * The Sky row — whether the room is lit as though it stood outdoors, and under which sky.
     *
     * <p>Shown for every portal room, on the same reasoning as Contents and Books: the sky a room
     * stands under is a statement about the place it is pretending to be, not about how it seals or
     * what is furnished into it.</p>
     */
    public static CommandMenuEntry roomSkyRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.roomSkyLabel(currentMode),
            prefix + " sky next");
    }

    /**
     * The Fog row — whether the room hides its boundary behind fog: Auto (the walls mode's own
     * answer), On or Off.
     *
     * <p>Shown for every portal room, directly under Sky: like the sky, the fog is a statement about
     * the place the room is pretending to be, and an author may want it in a sealed room or out of
     * an endless one.</p>
     */
    public static CommandMenuEntry roomFogRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.roomFogLabel(currentMode),
            prefix + " fog next");
    }

    /**
     * The Exits row, or null unless the walls repeat — only an endless room has anywhere to put an
     * extra way back to the train.
     *
     * <p>Shown for <b>both</b> endless modes, unlike Copies. What Copies describes is what an
     * appended tile contains, which Endless Open decides for itself; getting lost is not a property
     * of the walls, so an Endless Open room is asked the question too — it just answers Off by
     * default.</p>
     */
    public static CommandMenuEntry exitsRowFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        if (!PortalRoomSettings.parse(currentMode).exitsApply()) return null;
        return new CommandMenuEntry.Stay(
            EditorPlotLabelsRenderer.exitsLabel(currentMode),
            prefix + " exits next");
    }

    /**
     * The stepper for the {@code X} in "every X tiles" / "one tile in X", or null when Exits is not
     * showing or is set to Off.
     *
     * <p>Hidden rather than greyed out at Off, because a spacing for corridors that are not being
     * laid is a control with nothing on the other end of it — the same reason the Copies row is
     * absent rather than dimmed under a mode that makes no copies.</p>
     */
    public static CommandMenuEntry exitEveryTripleFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        PortalRoomSettings settings = PortalRoomSettings.parse(currentMode);
        if (!settings.exitsApply() || !settings.exits().lays()) return null;

        String command = prefix + " exitevery";
        CommandMenuEntry minus = new CommandMenuEntry.Stay("-", command + " dec");
        CommandMenuEntry middle = new CommandMenuEntry.TypeArg(
            EditorPlotLabelsRenderer.exitEveryLabel(currentMode), "tiles", command);
        CommandMenuEntry plus = new CommandMenuEntry.Stay("+", command + " inc");
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }

    /**
     * The moved-exit stepper, or null unless Exits is set to Random.
     *
     * <p>Random alone: walling off the way straight back out is only fair when there is something
     * unpredictable to go and find. Under the lattice a player could work out the walk in advance,
     * and under Off it would wall the only way onward there is.</p>
     */
    public static CommandMenuEntry exitMoveTripleFor(String currentMode, String prefix) {
        if (currentMode == null || EditorStatusPacket.NO_MODE.equals(currentMode)) return null;
        PortalRoomSettings settings = PortalRoomSettings.parse(currentMode);
        if (!settings.exitsApply() || !settings.exits().movesApply()) return null;

        String command = prefix + " exitmove";
        CommandMenuEntry minus = new CommandMenuEntry.Stay("-", command + " dec");
        CommandMenuEntry middle = new CommandMenuEntry.TypeArg(
            EditorPlotLabelsRenderer.exitMoveLabel(currentMode), "0-10", command);
        CommandMenuEntry plus = new CommandMenuEntry.Stay("+", command + " inc");
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }

    /**
     * A stepper for one axis of a portal room's box, or null when the server hasn't reported a size
     * (i.e. this isn't a portal room plot).
     *
     * <p>Side cells nudge by one and keep the menu open so the player can tap; the middle cell drops
     * into typing mode for an exact value. Values are clamped server-side: width and height cannot
     * go below what the corridor mouth needs to stay sealed, and height cannot reach into the next
     * portal pair's Y lane. Tapping {@code −} past the floor simply stops.</p>
     */
    public static CommandMenuEntry sizeTripleFor(String axis, String label, int current, String prefix) {
        if (current == EditorStatusPacket.NO_SIZE) return null;
        String command = prefix + " " + axis;
        CommandMenuEntry minus = new CommandMenuEntry.Stay("-", command + " dec");
        CommandMenuEntry middle = new CommandMenuEntry.TypeArg(
            label + " (" + current + ")", "blocks", command);
        CommandMenuEntry plus = new CommandMenuEntry.Stay("+", command + " inc");
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }

    // ------------------------------------------------------------------
    // Stood-in overloads — the bare root, for the world-space menu and the tests
    // ------------------------------------------------------------------

    /** {@link #wallsModeRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry wallsModeRowFor(String currentMode) {
        return wallsModeRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #lockRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry lockRowFor(String currentMode) {
        return lockRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #copiesRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry copiesRowFor(String currentMode) {
        return copiesRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #doorWallRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry doorWallRowFor(String currentMode) {
        return doorWallRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #roomContentsRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry roomContentsRowFor(String currentMode) {
        return roomContentsRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #roomBooksRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry roomBooksRowFor(String currentMode) {
        return roomBooksRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #roomSkyRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry roomSkyRowFor(String currentMode) {
        return roomSkyRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #roomFogRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry roomFogRowFor(String currentMode) {
        return roomFogRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #exitsRowFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry exitsRowFor(String currentMode) {
        return exitsRowFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #exitEveryTripleFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry exitEveryTripleFor(String currentMode) {
        return exitEveryTripleFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #exitMoveTripleFor(String, String)} for the room the author is standing in. */
    public static CommandMenuEntry exitMoveTripleFor(String currentMode) {
        return exitMoveTripleFor(currentMode, STOOD_IN_PREFIX);
    }

    /** {@link #copiesBlockRowFor(String, PortalRoomCopiesVariant.Plane, String)} for the stood-in room. */
    public static CommandMenuEntry copiesBlockRowFor(String currentMode, PortalRoomCopiesVariant.Plane plane) {
        return copiesBlockRowFor(currentMode, plane, STOOD_IN_PREFIX);
    }

    /** {@link #sizeTripleFor(String, String, int, String)} for the stood-in room. */
    public static CommandMenuEntry sizeTripleFor(String axis, String label, int current) {
        return sizeTripleFor(axis, label, current, STOOD_IN_PREFIX);
    }
}
