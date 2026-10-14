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
 *   <li>A screen open (the chat box, a sign, a book, an inventory, another mod's menu): never
 *       broadcast. The frame is read before the screen draws, as the Options preview does
 *       ({@link #previewPoint}) — after the GUI with chat on, before the chat layer with it off.</li>
 *   <li>GUI off: before the GUI — the world and the hand only, never a HUD or a menu.</li>
 *   <li>Chat off: just before the chat layer — the HUD, but no chat lines or tab list.</li>
 *   <li>Otherwise: the finished frame.</li>
 * </ul>
 *
 * <p>So the chat switch only governs the chat lines on the HUD — whispers included — and what is
 * being typed never goes out.</p>
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
     * @param gui        viewers see the HUD
     * @param chat       viewers see chat ({@link #chatShown} already applied)
     * @param screenOpen any screen is open — it is never broadcast
     * @param hideGui    the GUI is hidden (F1)
     */
    public static Point point(boolean gui, boolean chat, boolean screenOpen, boolean hideGui) {
        if (screenOpen) return previewPoint(gui, chat);
        if (!gui) return Point.BEFORE_GUI;
        if (chat || hideGui) return Point.FRAME_END;
        return Point.BEFORE_CHAT;
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
