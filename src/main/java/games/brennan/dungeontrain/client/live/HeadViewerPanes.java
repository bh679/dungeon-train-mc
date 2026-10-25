package games.brennan.dungeontrain.client.live;

import java.util.List;

/**
 * What the pinned head-viewer box shows while two streams are live (the Two Streams reward): both
 * streams by default, the first stream in its usual top-right spot and the second right under it;
 * from the inventory a click on a pane makes that stream the only one, and a click on the lone pane
 * brings the other back. With one stream live, or with the box at its largest size (where two would
 * not fit), there is one pane, exactly as before.
 *
 * <p>The choice is session state: it is forgotten on logout and whenever the second stream goes
 * dark, so a lone stream never looks "soloed". Pure apart from that one field, so the layout and
 * the click rules are pinned by tests.</p>
 */
public final class HeadViewerPanes {

    /** Which panes the wearer asked for. */
    public enum Choice { BOTH, MAIN_ONLY, SECOND_ONLY }

    /** One pane: which stream ({@code second}) and where it is drawn. */
    public record Pane(boolean second, LiveHeadViewer.Box box) {}

    /** Space between the two panes; the same as the gap to the screen edge. */
    static final int GAP = LiveHeadViewer.EDGE;

    private static volatile Choice choice = Choice.BOTH;
    /** The remembered stream (0 / 1 / 2) of the TV last seen on the head; -1 before any. */
    private static volatile int wornMemory = -1;

    private HeadViewerPanes() {}

    /** The choice a worn TV's memory ({@code memory} 1 or 2) asks for, else the settings default. Pure. */
    static Choice choiceFor(int memory, games.brennan.dungeontrain.config.LiveFeedClientConfig.WornTvStreams option) {
        if (memory == 1) return Choice.MAIN_ONLY;
        if (memory == 2) return Choice.SECOND_ONLY;
        return switch (option) {
            case STREAM_1 -> Choice.MAIN_ONLY;
            case STREAM_2 -> Choice.SECOND_ONLY;
            case BOTH -> Choice.BOTH;
        };
    }

    /** What a choice writes onto the worn TV ({@code TV_STREAM}): 1, 2, or 0 for both. Pure. */
    static int memoryOf(Choice c) {
        return switch (c) { case MAIN_ONLY -> 1; case SECOND_ONLY -> 2; case BOTH -> 0; };
    }

    /**
     * The TV on the head now remembers {@code memory} (0 none, 1, 2): when that differs from the TV
     * last seen (a different TV put on, or the server echoing a solo), the choice follows it, so a
     * TV comes back on the stream it was set to and an unset one on the settings default.
     */
    public static void wearing(int memory, games.brennan.dungeontrain.config.LiveFeedClientConfig.WornTvStreams option) {
        if (memory == wornMemory) return;
        wornMemory = memory;
        choice = choiceFor(memory, option);
    }

    public static Choice choice() {
        return choice;
    }

    /** World left, or the second stream went dark: back to both. */
    public static void reset() {
        choice = Choice.BOTH;
        wornMemory = -1;
    }

    /**
     * The panes to draw now. Forgets a solo choice when the second stream is no longer live, so the
     * next pair starts stacked again. {@code atMaxSize}: the box is at its largest, so only one pane
     * (the chosen stream, or the first) is shown.
     */
    public static List<Pane> current(int guiWidth, int boxWidth, boolean secondLive, boolean atMaxSize) {
        return current(guiWidth, boxWidth, secondLive, atMaxSize, 16f / 9f, 16f / 9f);
    }

    /** {@link #current(int, int, boolean, boolean)} with each stream's own shape (width over height). */
    public static List<Pane> current(int guiWidth, int boxWidth, boolean secondLive, boolean atMaxSize,
                                     float mainRatio, float secondRatio) {
        if (!secondLive) { choice = Choice.BOTH; wornMemory = -1; } // the worn TV's memory applies again when a second returns
        return panes(guiWidth, boxWidth, secondLive && !atMaxSize, choice, mainRatio, secondRatio);
    }

    /**
     * Pure layout: one pane with one stream; with two ({@code twoAllowed}), both stacked (the second
     * under the first) or the chosen one alone in the first pane's spot.
     */
    static List<Pane> panes(int guiWidth, int boxWidth, boolean twoAllowed, Choice choice) {
        return panes(guiWidth, boxWidth, twoAllowed, choice, 16f / 9f, 16f / 9f);
    }

    /** {@link #panes(int, int, boolean, Choice)} with each pane as tall as its own stream's shape needs. */
    static List<Pane> panes(int guiWidth, int boxWidth, boolean twoAllowed, Choice choice, float mainRatio, float secondRatio) {
        LiveHeadViewer.Box first = LiveHeadViewer.layout(guiWidth, boxWidth, mainRatio);
        if (!twoAllowed) return List.of(new Pane(false, first));
        LiveHeadViewer.Box secondAlone = LiveHeadViewer.layout(guiWidth, boxWidth, secondRatio);
        return switch (choice) {
            case MAIN_ONLY -> List.of(new Pane(false, first));
            case SECOND_ONLY -> List.of(new Pane(true, secondAlone));
            case BOTH -> List.of(new Pane(false, first),
                new Pane(true, new LiveHeadViewer.Box(first.x(), first.y() + first.h() + 2 * LiveHeadViewer.BORDER + GAP,
                    boxWidth, secondAlone.h())));
        };
    }

    /**
     * The choice after a click at ({@code x}, {@code y}) on {@code panes}: a pane clicked while both show
     * becomes the only one; the lone pane clicked brings both back; a click elsewhere changes nothing.
     */
    static Choice click(List<Pane> panes, Choice choice, double x, double y) {
        for (Pane p : panes) {
            if (!LiveHeadViewer.contains(p.box(), x, y)) continue;
            if (choice == Choice.BOTH) return p.second() ? Choice.SECOND_ONLY : Choice.MAIN_ONLY;
            return Choice.BOTH;
        }
        return choice;
    }

    /** Applies {@link #click} to the session choice; true when it changed (the click was on a pane). */
    public static boolean apply(List<Pane> panes, double x, double y) {
        Choice next = click(panes, choice, x, y);
        if (next == choice) return false;
        choice = next;
        wornMemory = memoryOf(next); // the server writes the same onto the TV; its echo must not undo the click
        return true;
    }
}
