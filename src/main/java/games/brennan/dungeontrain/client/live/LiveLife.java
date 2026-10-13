package games.brennan.dungeontrain.client.live;

/**
 * The life this client's player is on, as the server last said ({@code net/LiveLifePacket}). Passed
 * to the relay with a stream claim and a viewer's status poll, for the one-life Live Feed boards.
 * Empty until the server says, and on a server without the packet — the relay then credits only
 * the all-time boards.
 */
public final class LiveLife {

    private static volatile String current = "";

    private LiveLife() {}

    public static String current() {
        return current;
    }

    public static void set(String lifeId) {
        current = lifeId == null || !lifeId.matches("[0-9a-f]{32}") ? "" : lifeId;
    }
}
