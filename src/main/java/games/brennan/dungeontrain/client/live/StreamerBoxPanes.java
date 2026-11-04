package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.LiveFeedClientConfig.StreamerBoxStreams;

import java.util.List;

/**
 * What the streamer's own pinned box shows while someone else is live too (the Two Streams reward):
 * their own stream, the other one, both (theirs in the usual top-right spot, the other right under it),
 * or nothing. The same panes and click rules a viewer's box has ({@link HeadViewerPanes}), with the
 * streamer's own stream in the first pane's place: in a {@link HeadViewerPanes.Pane} here,
 * {@code second()} means "the other stream".
 *
 * <p>Each stream starts from the Options setting ({@code streamerBoxStreams}, default {@link StreamerBoxStreams#OWN});
 * an inventory click switches it until that stream ends. With nobody else live the box shows their own
 * stream, unless the setting is {@link StreamerBoxStreams#NONE}, and the next pair starts from the setting
 * again. Pure apart from that session state, so the rules are pinned by tests.</p>
 */
public final class StreamerBoxPanes {

    /** The session choice, or null to take the setting. */
    private static volatile StreamerBoxStreams choice;
    /** The start of the stream {@link #choice} was made in; a new stream starts from the setting. */
    private static volatile long choiceStreamMs = -1L;

    private StreamerBoxPanes() {}

    /** Forget the session choice: the setting applies again (logout, the setting changed). */
    public static void reset() {
        choice = null;
        choiceStreamMs = -1L;
    }

    /** The choice for the stream that started at {@code streamStartedMs}: the clicked one, else {@code option}. */
    static StreamerBoxStreams choice(long streamStartedMs, StreamerBoxStreams option) {
        StreamerBoxStreams c = choice;
        return c != null && choiceStreamMs == streamStartedMs ? c : option;
    }

    /** The viewer pane choice a streamer choice draws as: own in the first pane's place, the other in the second's. */
    static HeadViewerPanes.Choice asPaneChoice(StreamerBoxStreams c) {
        return switch (c) {
            case OTHER -> HeadViewerPanes.Choice.SECOND_ONLY;
            case BOTH -> HeadViewerPanes.Choice.BOTH;
            case OWN, NONE -> HeadViewerPanes.Choice.MAIN_ONLY;
        };
    }

    private static StreamerBoxStreams fromPaneChoice(HeadViewerPanes.Choice c) {
        return switch (c) {
            case SECOND_ONLY -> StreamerBoxStreams.OTHER;
            case BOTH -> StreamerBoxStreams.BOTH;
            case MAIN_ONLY -> StreamerBoxStreams.OWN;
        };
    }

    /**
     * Pure layout: nothing for {@link StreamerBoxStreams#NONE}; their own pane alone with nobody else live;
     * else {@link HeadViewerPanes#panes} with own as the first stream and the other as the second.
     */
    static List<HeadViewerPanes.Pane> panes(int guiWidth, int guiHeight, int boxWidth, boolean otherLive, boolean atMaxSize,
                                            StreamerBoxStreams choice, float ownRatio, float otherRatio) {
        if (choice == StreamerBoxStreams.NONE) return List.of();
        return HeadViewerPanes.panes(guiWidth, guiHeight, boxWidth, otherLive, atMaxSize, asPaneChoice(choice),
            ownRatio, otherRatio);
    }

    /** The panes to draw now, for the stream that started at {@code streamStartedMs}. Forgets a click once the other stream is gone. */
    static List<HeadViewerPanes.Pane> current(int guiWidth, int guiHeight, int boxWidth, boolean otherLive, boolean atMaxSize,
                                              long streamStartedMs, StreamerBoxStreams option, float ownRatio, float otherRatio) {
        if (!otherLive) reset(); // the next pair starts from the setting again
        return panes(guiWidth, guiHeight, boxWidth, otherLive, atMaxSize, choice(streamStartedMs, option), ownRatio, otherRatio);
    }

    /**
     * The choice after a click at ({@code x}, {@code y}): a pane clicked while both show becomes the only one;
     * the lone pane clicked brings both back; elsewhere nothing changes ({@link HeadViewerPanes#click}).
     */
    static StreamerBoxStreams click(List<HeadViewerPanes.Pane> panes, StreamerBoxStreams current, double x, double y) {
        if (current == StreamerBoxStreams.NONE || panes.isEmpty()) return current;
        HeadViewerPanes.Choice now = asPaneChoice(current);
        HeadViewerPanes.Choice next = HeadViewerPanes.click(panes, now, x, y);
        return next == now ? current : fromPaneChoice(next);
    }

    /** Applies {@link #click} to the session choice for this stream; true when it changed (the click was on a pane). */
    static boolean apply(List<HeadViewerPanes.Pane> panes, double x, double y, long streamStartedMs, StreamerBoxStreams option) {
        StreamerBoxStreams now = choice(streamStartedMs, option);
        StreamerBoxStreams next = click(panes, now, x, y);
        if (next == now) return false;
        choice = next;
        choiceStreamMs = streamStartedMs;
        return true;
    }
}
