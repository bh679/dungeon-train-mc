package games.brennan.dungeontrain.client.live;

import java.util.List;

/**
 * What the pinned head-viewer box shows while two streams are live (the Two Streams reward): both
 * streams by default, the first stream in its usual top-right spot and the second right under it;
 * from the inventory a click on a pane makes that stream the only one, and a click on the lone pane
 * brings the other back. With one stream live there is one pane, exactly as before. With the box at
 * its largest size, or a window too short for both panes (and the takeover button under them), there
 * is one pane too: the chosen stream, or the first.
 *
 * <p>The choice is session state: it is forgotten on logout and whenever the second stream goes
 * dark, so a lone stream never looks "soloed". A worn Vista TV remembers it ({@code TV_STREAM}), so
 * putting one on picks its stream; a click is held until the server echoes it onto the TV (or
 * {@link #PENDING_MS} passes), so the picture never flicks back for the round trip. A Live Eye has
 * no memory: a click there simply sticks for the session. Pure apart from that state, so the layout
 * and the click rules are pinned by tests.</p>
 */
public final class HeadViewerPanes {

    /** Which panes the wearer asked for. */
    public enum Choice { BOTH, MAIN_ONLY, SECOND_ONLY }

    /** One pane: which stream ({@code second}) and where it is drawn. */
    public record Pane(boolean second, LiveHeadViewer.Box box) {}

    /** Space between the two panes; the same as the gap to the screen edge. */
    static final int GAP = LiveHeadViewer.EDGE;
    /** Space between the first pane's caption and the second pane. */
    static final int CAPTION_GAP = 2;
    /** How long a click is held waiting for the server to write it onto the worn TV. */
    static final long PENDING_MS = 2_000L;
    /** {@link #wornMemory} for a head item that is not a Vista TV (the Live Eye): it has no memory to follow. */
    static final int NOT_TV = -2;

    private static volatile Choice choice = Choice.BOTH;
    /** The remembered stream (0 / 1 / 2) of the TV last seen on the head, {@link #NOT_TV}, or -1 before any. */
    private static volatile int wornMemory = -1;
    /** The memory a click asked the server to write, until it shows up on the TV; -1 when none is waiting. */
    private static volatile int pendingMemory = -1;
    private static volatile long pendingAtMs;

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
     * What is on the head now: a Vista TV ({@code isTv}) remembering {@code memory} (0 none, 1, 2), or a
     * Live Eye. When that differs from what was last seen (a different TV put on), the choice follows
     * it, so a TV comes back on the stream it was set to and an unset one on the settings default. A
     * click waiting for its echo is held meanwhile; the echo confirms it.
     */
    public static void wearing(boolean isTv, int memory,
                               games.brennan.dungeontrain.config.LiveFeedClientConfig.WornTvStreams option, long nowMs) {
        int seen = isTv ? memory : NOT_TV;
        if (pendingMemory >= 0) {
            if (seen == pendingMemory) {
                wornMemory = seen; // the server wrote the click onto the TV
                pendingMemory = -1;
                return;
            }
            if (nowMs - pendingAtMs < PENDING_MS) return; // not echoed yet: keep the clicked choice
            pendingMemory = -1; // no echo (a Live Eye, or a creative screen undid it): the click stays
        }
        if (seen == wornMemory) return;
        wornMemory = seen;
        choice = choiceFor(isTv ? memory : 0, option);
    }

    public static Choice choice() {
        return choice;
    }

    /** World left, or the second stream went dark: back to both. */
    public static void reset() {
        choice = Choice.BOTH;
        wornMemory = -1;
        pendingMemory = -1;
    }

    /**
     * The panes to draw now. Forgets a solo choice when the second stream is no longer live, so the
     * next pair starts stacked again. {@code atMaxSize}: the box is at its largest, so only one pane
     * (the chosen stream, or the first) is shown.
     */
    public static List<Pane> current(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize) {
        return current(guiWidth, guiHeight, boxWidth, secondLive, atMaxSize, 16f / 9f, 16f / 9f);
    }

    /** {@link #current(int, int, int, boolean, boolean)} with each stream's own shape (width over height). */
    public static List<Pane> current(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize,
                                     float mainRatio, float secondRatio) {
        return current(guiWidth, guiHeight, boxWidth, secondLive, atMaxSize, mainRatio, secondRatio, 0);
    }

    /** {@link #current(int, int, int, boolean, boolean, float, float)} with {@code caption} GUI pixels kept under each pane. */
    public static List<Pane> current(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize,
                                     float mainRatio, float secondRatio, int caption) {
        if (!secondLive) reset(); // the worn TV's memory applies again when a second returns
        return panes(guiWidth, guiHeight, boxWidth, secondLive, atMaxSize, choice, mainRatio, secondRatio, caption);
    }

    /**
     * Whether the two panes can be stacked: the box is not at its largest and the second pane, with
     * the takeover button under it, ends on screen. Pure.
     */
    public static boolean twoFit(int guiWidth, int guiHeight, int boxWidth, boolean atMaxSize, float mainRatio, float secondRatio) {
        return twoFit(guiWidth, guiHeight, boxWidth, atMaxSize, mainRatio, secondRatio, 0);
    }

    /** {@link #twoFit(int, int, int, boolean, float, float)} with {@code caption} GUI pixels under each pane. Pure. */
    public static boolean twoFit(int guiWidth, int guiHeight, int boxWidth, boolean atMaxSize, float mainRatio, float secondRatio,
                                 int caption) {
        if (atMaxSize) return false;
        LiveHeadViewer.Box second = stacked(guiWidth, boxWidth, mainRatio, secondRatio, caption);
        int bottom = second.y() + second.h() + LiveHeadViewer.BORDER + caption + LiveTakeoverButton.GAP + LiveTakeoverButton.H;
        return bottom <= guiHeight;
    }

    /**
     * Pure layout: one pane with one stream; with two, both stacked (the second under the first) or the
     * chosen one alone in the first pane's spot, and only the chosen one (or the first) when two do not
     * fit ({@link #twoFit}).
     */
    static List<Pane> panes(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize, Choice choice) {
        return panes(guiWidth, guiHeight, boxWidth, secondLive, atMaxSize, choice, 16f / 9f, 16f / 9f);
    }

    /** {@link #panes(int, int, int, boolean, boolean, Choice)} with each pane as tall as its own stream's shape needs. */
    static List<Pane> panes(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize, Choice choice,
                            float mainRatio, float secondRatio) {
        return panes(guiWidth, guiHeight, boxWidth, secondLive, atMaxSize, choice, mainRatio, secondRatio, 0);
    }

    /** {@link #panes(int, int, int, boolean, boolean, Choice, float, float)} with {@code caption} GUI pixels under each pane. */
    static List<Pane> panes(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize, Choice choice,
                            float mainRatio, float secondRatio, int caption) {
        LiveHeadViewer.Box first = LiveHeadViewer.layout(guiWidth, boxWidth, mainRatio);
        if (!secondLive) return List.of(new Pane(false, first));
        if (choice == Choice.SECOND_ONLY) return List.of(new Pane(true, LiveHeadViewer.layout(guiWidth, boxWidth, secondRatio)));
        if (choice == Choice.MAIN_ONLY || !twoFit(guiWidth, guiHeight, boxWidth, atMaxSize, mainRatio, secondRatio, caption)) {
            return List.of(new Pane(false, first));
        }
        return List.of(new Pane(false, first), new Pane(true, stacked(guiWidth, boxWidth, mainRatio, secondRatio, caption)));
    }

    /** The second pane's box when it sits under the first (and the first's {@code caption}). */
    private static LiveHeadViewer.Box stacked(int guiWidth, int boxWidth, float mainRatio, float secondRatio, int caption) {
        LiveHeadViewer.Box first = LiveHeadViewer.layout(guiWidth, boxWidth, mainRatio);
        LiveHeadViewer.Box secondAlone = LiveHeadViewer.layout(guiWidth, boxWidth, secondRatio);
        return new LiveHeadViewer.Box(first.x(), first.y() + first.h() + 2 * LiveHeadViewer.BORDER + between(caption), boxWidth,
            secondAlone.h());
    }

    /**
     * Space between the two panes: the plain {@link #GAP}, or the first pane's caption sitting in it with
     * {@link #CAPTION_GAP} under it. The caption fills the gap rather than adding to it, so both panes and
     * their captions still fit a 240-GUI-pixel-tall screen at the normal box size. Pure.
     */
    static int between(int caption) {
        return caption == 0 ? GAP : Math.max(GAP, caption + CAPTION_GAP);
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

    /**
     * Applies {@link #click} to the session choice; true when it changed (the click was on a pane). The
     * caller sends the choice to the server for the worn TV; until it echoes back the choice is held.
     */
    public static boolean apply(List<Pane> panes, double x, double y, long nowMs) {
        Choice next = click(panes, choice, x, y);
        if (next == choice) return false;
        choice = next;
        pendingMemory = memoryOf(next);
        pendingAtMs = nowMs;
        return true;
    }
}
