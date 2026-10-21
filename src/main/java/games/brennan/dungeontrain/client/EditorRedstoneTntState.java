package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of the world's editor Redstone and TNT settings, pushed by
 * {@link games.brennan.dungeontrain.net.EditorRedstoneTntPacket}. Read by the Settings rows; the
 * rules themselves are server-side ({@code EditorRedstone}, {@code EditorTnt}).
 */
public final class EditorRedstoneTntState {

    /** Mutated on the main client thread from the packet handler; read from the render thread. */
    private static volatile boolean redstoneOn = true;
    private static volatile boolean tntOn = true;

    private EditorRedstoneTntState() {}

    public static boolean redstoneOn() {
        return redstoneOn;
    }

    public static boolean tntOn() {
        return tntOn;
    }

    public static void set(boolean redstone, boolean tnt) {
        redstoneOn = redstone;
        tntOn = tnt;
    }
}
