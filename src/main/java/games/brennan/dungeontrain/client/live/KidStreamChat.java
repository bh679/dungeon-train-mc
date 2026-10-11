package games.brennan.dungeontrain.client.live;

/**
 * Where a frame of the Live Feed is grabbed so that, for a Kid-mode streamer, chat never goes out.
 *
 * <p>The vanilla GUI layers draw in a fixed order and chat is near the top (only the tab list,
 * subtitles and saving indicator follow it); an open screen draws after all of them. So a frame read
 * just before the chat layer has the world and the HUD but no chat lines, no chat box and no tab
 * list. When some other screen is open the grab has to wait for it, so the chat layer is skipped
 * for that frame instead. With the GUI hidden (F1) no layer draws at all — but the chat box still
 * does, with whatever is being typed, so that frame is not sent. Adults are untouched.</p>
 */
public final class KidStreamChat {

    public enum Mode {
        /** Grab at the finished frame, as for any adult stream. */
        GRAB_AT_END,
        /** Grab just before the chat layer draws; the frame end must not grab again. */
        GRAB_BEFORE_CHAT,
        /** A menu is open: skip drawing the chat layer, then grab at the finished frame. */
        HIDE_CHAT_GRAB_AT_END,
        /** Nothing safe to grab this frame (chat box open with the GUI hidden). */
        SKIP
    }

    private KidStreamChat() {}

    /**
     * @param kid        the streamer is in Kid mode
     * @param screenOpen any screen is open
     * @param chatScreen the open screen is the chat box (or one of its subclasses, e.g. in bed)
     * @param hideGui    the GUI is hidden (F1), so no GUI layer draws
     */
    public static Mode mode(boolean kid, boolean screenOpen, boolean chatScreen, boolean hideGui) {
        if (!kid) return Mode.GRAB_AT_END;
        if (hideGui) return chatScreen ? Mode.SKIP : Mode.GRAB_AT_END;
        if (!screenOpen || chatScreen) return Mode.GRAB_BEFORE_CHAT;
        return Mode.HIDE_CHAT_GRAB_AT_END;
    }
}
