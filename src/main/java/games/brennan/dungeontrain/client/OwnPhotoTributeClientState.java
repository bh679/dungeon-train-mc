package games.brennan.dungeontrain.client;

/**
 * The client's copy of what its next own-photo Tribute costs ({@code OwnPhotoTributeCostPacket}).
 * 0 — the default before the server says otherwise — hides the button.
 */
public final class OwnPhotoTributeClientState {

    private static volatile int cost;

    private OwnPhotoTributeClientState() {}

    public static int cost() {
        return cost;
    }

    public static void setCost(int value) {
        cost = Math.max(0, value);
    }
}
