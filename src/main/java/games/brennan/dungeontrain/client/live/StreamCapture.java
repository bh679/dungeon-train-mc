package games.brennan.dungeontrain.client.live;

/**
 * Where in the frame the Live Feed reads its picture, so viewers get exactly what the streamer's
 * Stream settings allow — and the Options preview shows the same thing.
 *
 * <p>Every in-world frame draws the world, then the GUI layers in a fixed order (hotbar, health, F3,
 * scoreboard, title … then chat, then the tab list, subtitles and saving indicator), then whatever
 * screen is open. NeoForge fires an event before the GUI, before each layer and after the GUI on
 * every in-world frame, even with the GUI hidden (F1) — the hide check sits inside each layer. So
 * the frame can be read at the point that leaves out what must not be shown:</p>
 *
 * <ul>
 *   <li>GUI off: before the GUI — the world and the hand only, never a HUD or a menu.</li>
 *   <li>Chat off: just before the chat layer — the HUD, but no chat lines, chat box or tab list.
 *       When some other menu is open the read has to wait for it, so the chat layer is skipped
 *       for that frame instead. With F1 no chat line draws, but the chat box still does, with
 *       whatever is being typed, so that frame is not sent.</li>
 *   <li>Otherwise: the finished frame.</li>
 * </ul>
 *
 * <p>Chat is always off in Kid mode ({@link #chatShown}).</p>
 */
public final class StreamCapture {

    public enum Point {
        /** Before any GUI layer draws. */
        BEFORE_GUI,
        /** Just before the chat layer draws. */
        BEFORE_CHAT,
        /** After every GUI layer, before the open screen (the preview's "no menu" frame). */
        AFTER_GUI,
        /** The finished frame. */
        FRAME_END,
        /** The finished frame, with the chat layer skipped. */
        FRAME_END_HIDE_CHAT,
        /** Nothing safe to send this frame. */
        SKIP
    }

    private StreamCapture() {}

    /** The chat switch as it applies: Kid mode never shows chat. */
    public static boolean chatShown(boolean streamChat, boolean kid) {
        return streamChat && !kid;
    }

    /**
     * Where the broadcast reads this frame.
     *
     * @param gui        viewers see the HUD and menus
     * @param chat       viewers see chat ({@link #chatShown} already applied)
     * @param screenOpen any screen is open
     * @param chatScreen the open screen is the chat box (or one of its subclasses, e.g. in bed)
     * @param hideGui    the GUI is hidden (F1)
     */
    public static Point point(boolean gui, boolean chat, boolean screenOpen, boolean chatScreen, boolean hideGui) {
        if (!gui) return Point.BEFORE_GUI;
        if (chat) return Point.FRAME_END;
        if (hideGui) return chatScreen ? Point.SKIP : Point.FRAME_END;
        if (!screenOpen || chatScreen) return Point.BEFORE_CHAT;
        return Point.FRAME_END_HIDE_CHAT;
    }

    /**
     * Where the Options preview reads: as {@link #point} with no menu open, since the Options screen
     * itself is never what viewers would be watching.
     */
    public static Point previewPoint(boolean gui, boolean chat) {
        if (!gui) return Point.BEFORE_GUI;
        if (!chat) return Point.BEFORE_CHAT;
        return Point.AFTER_GUI;
    }
}
