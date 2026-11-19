package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.jetbrains.annotations.Nullable;

/**
 * Which Live chat room this client is in while two streams are on air (the Two Streams reward): each
 * stream has its own room on the relay, named by its slot ({@code main} / {@code second}).
 *
 * <p>A streamer is in their own stream's room. A worn TV soloed on one stream ({@link HeadViewerPanes})
 * is in that stream's room; one showing both is in the room picked with the Stream 1 / Stream 2 toggle
 * above the chat input ({@link LiveChatRoomToggle}), Stream 1 by default. A Live Eye waiting on a
 * takeover reads Stream 1. Stream 1 and 2 follow the status: Stream 1 is its primary, whatever slot that
 * is, and Stream 2 its {@code second} block.</p>
 *
 * <p>With one stream up there is no room at all ({@link #pick} returns null): the relay serves the one
 * room there always was, and nothing reads differently. The toggle choice is session state, forgotten on
 * logout and whenever the second stream goes dark. Pure apart from that, so the rules are pinned by tests.</p>
 */
public final class LiveChatRoom {

    /** Who is reading the chat. */
    public enum Role { STREAMER, EYE, TV }

    /** The room picked: its slot, which of the pair it is (1 or 2), and who streams it (may be null). */
    public record Pick(String room, int stream, @Nullable String streamer) {}

    private static volatile int chosen = 1;

    private LiveChatRoom() {}

    /** The toggle's room: 1 (Stream 1, the default) or 2. */
    public static int chosen() {
        return chosen;
    }

    /** The toggle was clicked: {@code stream} 1 or 2; anything else is ignored. */
    public static void choose(int stream) {
        if (stream == 1 || stream == 2) chosen = stream;
    }

    /** Logout, or the second stream went dark: back to Stream 1. */
    public static void reset() {
        chosen = 1;
    }

    /**
     * The room to read and post in, or null with fewer than two streams up. {@code onSecond}: a streamer's
     * claim is on the second slot. {@code panes}: what a worn TV shows. {@code chosen}: the toggle. Pure.
     */
    @Nullable
    public static Pick pick(Status status, Role role, boolean onSecond, HeadViewerPanes.Choice panes, int chosen) {
        Status second = status.live() ? status.second() : null;
        // A streamer on the second slot is always in that room: their own status may not be polled, and
        // with no room named the relay would serve the primary stream's.
        if (role == Role.STREAMER && onSecond && second == null) return new Pick(LiveFeedClient.CHANNEL_SECOND, 2, null);
        if (second == null) return null;
        int stream = switch (role) {
            case STREAMER -> streamerStream(status, onSecond);
            case EYE -> 1;
            case TV -> switch (panes) {
                case MAIN_ONLY -> 1;
                case SECOND_ONLY -> 2;
                case BOTH -> chosen == 2 ? 2 : 1;
            };
        };
        Status s = stream == 2 ? second : status;
        // A streamer's room is their claim's slot, whatever the status calls it.
        String room = role == Role.STREAMER
            ? (onSecond ? LiveFeedClient.CHANNEL_SECOND : LiveFeedClient.CHANNEL_MAIN)
            : LiveFeedClient.roomOf(s, stream == 2);
        return new Pick(room, stream, s.streamer());
    }

    /** Which of the pair a streamer's own stream is: the one whose slot matches their claim. */
    private static int streamerStream(Status status, boolean onSecond) {
        String mine = onSecond ? LiveFeedClient.CHANNEL_SECOND : LiveFeedClient.CHANNEL_MAIN;
        return mine.equals(LiveFeedClient.roomOf(status.second(), true)) ? 2 : 1;
    }

    /** The slot of a pick, or null for none. Pure. */
    @Nullable
    public static String roomOf(@Nullable Pick pick) {
        return pick == null ? null : pick.room();
    }

    /**
     * Whether the Stream 1 / Stream 2 toggle shows: two streams up and this player in the chat as a worn
     * TV showing both. A streamer, a Live Eye or a soloed TV already has its room. Pure.
     */
    public static boolean showToggle(boolean connected, Status status, Role role, HeadViewerPanes.Choice panes) {
        return connected && role == Role.TV && panes == HeadViewerPanes.Choice.BOTH
            && status.live() && status.second() != null;
    }
}
