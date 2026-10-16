package games.brennan.dungeontrain.client.live;

/**
 * The popups (toasts) drawn early this frame, so a chat-off stream carries them.
 *
 * <p>Minecraft draws toasts last, after the HUD and any screen. With Stream chat off the broadcast is
 * read just before the chat layer ({@link StreamCapture.Point#BEFORE_CHAT}), long before that — so
 * {@link LiveStreamController} draws them there instead, and {@code GameRendererToastsMixin} skips the
 * usual end-of-frame draw so they are drawn once. Render thread only.</p>
 */
public final class EarlyToasts {

    private static boolean drawn;

    private EarlyToasts() {}

    /** The toasts were drawn ahead of the chat layer this frame. */
    static void mark() {
        drawn = true;
    }

    /** True once after {@link #mark}: the end-of-frame toast draw should be skipped. */
    public static boolean consume() {
        boolean was = drawn;
        drawn = false;
        return was;
    }
}
