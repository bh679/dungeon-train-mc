package games.brennan.dungeontrain.client.live;

import java.util.List;

/**
 * What the pinned head-viewer box shows while two streams are live (the Two Streams reward): both
 * streams side by side by default, the first stream in its usual top-right spot and the second to
 * its left; from the inventory a click on a pane makes that stream the only one, and a click on the
 * lone pane brings the other back. With one stream live there is one pane, exactly as before.
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

    private HeadViewerPanes() {}

    public static Choice choice() {
        return choice;
    }

    /** World left, or the second stream went dark: back to both. */
    public static void reset() {
        choice = Choice.BOTH;
    }

    /**
     * The panes to draw now. Forgets a solo choice when the second stream is no longer live, so the
     * next pair starts side by side again.
     */
    public static List<Pane> current(int guiWidth, int boxWidth, boolean secondLive) {
        if (!secondLive) choice = Choice.BOTH;
        return panes(guiWidth, boxWidth, secondLive, choice);
    }

    /** Pure layout: one pane with one stream; with two, both (second to the left) or the chosen one alone. */
    static List<Pane> panes(int guiWidth, int boxWidth, boolean secondLive, Choice choice) {
        LiveHeadViewer.Box first = LiveHeadViewer.layout(guiWidth, boxWidth);
        if (!secondLive) return List.of(new Pane(false, first));
        return switch (choice) {
            case MAIN_ONLY -> List.of(new Pane(false, first));
            case SECOND_ONLY -> List.of(new Pane(true, first));
            case BOTH -> List.of(new Pane(false, first),
                new Pane(true, new LiveHeadViewer.Box(first.x() - GAP - boxWidth, first.y(), boxWidth, first.h())));
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
        return true;
    }
}
