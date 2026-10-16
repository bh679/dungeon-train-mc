package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of the world's editor Test Mobs setting, pushed by
 * {@link games.brennan.dungeontrain.net.EditorTestMobsPacket}. Read by the Settings row; the rule
 * itself is server-side ({@code TestCopyMobs}).
 */
public final class EditorTestMobsState {

    /** Mutated on the main client thread from the packet handler; read from the render thread. */
    private static volatile boolean live = false;

    private EditorTestMobsState() {}

    /** True when test copies spawn frozen mobs alive (Live); false is the default, Frozen. */
    public static boolean live() {
        return live;
    }

    public static void setLive(boolean next) {
        live = next;
    }
}
