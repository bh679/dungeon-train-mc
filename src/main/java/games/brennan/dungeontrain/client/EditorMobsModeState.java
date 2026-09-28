package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of the world's editor Mobs setting, pushed by
 * {@link games.brennan.dungeontrain.net.EditorMobsModePacket}. Read by the Settings row; the
 * rule itself is server-side ({@code FrozenMobs}).
 */
public final class EditorMobsModeState {

    /** Mutated on the main client thread from the packet handler; read from the render thread. */
    private static volatile boolean live = false;

    private EditorMobsModeState() {}

    /** True when eggs spawn wandering mobs (Live); false is the default, Blocks. */
    public static boolean live() {
        return live;
    }

    public static void setLive(boolean next) {
        live = next;
    }

    /** Back to the default — for a fresh session, so a world set to Live is told, not assumed. */
    public static void reset() {
        live = false;
    }
}
