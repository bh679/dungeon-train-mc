package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of the world's editor Observers setting, pushed by
 * {@link games.brennan.dungeontrain.net.EditorObserversPacket}. Read by the Settings row; the
 * rule itself is server-side ({@code EditorObservers}).
 */
public final class EditorObserversState {

    /** Mutated on the main client thread from the packet handler; read from the render thread. */
    private static volatile boolean on = true;

    private EditorObserversState() {}

    public static boolean on() {
        return on;
    }

    public static void set(boolean next) {
        on = next;
    }

    /** Back to the default — for a fresh session, so a world with the setting Off is told, not assumed. */
    public static void reset() {
        on = true;
    }
}
