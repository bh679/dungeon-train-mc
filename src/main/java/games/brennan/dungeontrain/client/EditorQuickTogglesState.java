package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of the world's two quick-toggle settings (Mirror, Frame), pushed by
 * {@link games.brennan.dungeontrain.net.EditorQuickTogglesPacket}. Read by the X menu's ▼ dropdown,
 * the Settings Frame row and the Effortless Building preview mirror; the rules themselves are
 * server-side ({@code EditorQuickToggles}).
 */
public final class EditorQuickTogglesState {

    /** Set on the main client thread from the packet handler; read from the render thread. */
    private static volatile boolean mirrorOn = true;
    private static volatile boolean frameOn = true;

    private EditorQuickTogglesState() {}

    public static boolean mirrorOn() {
        return mirrorOn;
    }

    public static boolean frameOn() {
        return frameOn;
    }

    public static void set(boolean mirror, boolean frame) {
        mirrorOn = mirror;
        frameOn = frame;
    }
}
