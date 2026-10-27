package games.brennan.dungeontrain.perks.effects.trader;

/**
 * Client-side copy of {@link RestockVillagerStatusPacket}: charges left on the villager behind one
 * trade menu. Plain fields, no client classes, so the packet handler can live in common code.
 */
public final class RestockVillagerClientState {

    private static volatile int containerId = -1;
    private static volatile int remaining = 0;

    private RestockVillagerClientState() {}

    static void set(int menuId, int left) {
        remaining = Math.max(0, left);
        containerId = menuId;
    }

    /** Charges left for trade menu {@code menuId}, or -1 when the server sent none (perk inactive). */
    public static int remainingFor(int menuId) {
        return containerId == menuId ? remaining : -1;
    }
}
