package games.brennan.dungeontrain.perks.effects.trader;

/**
 * Client-side copy of {@link RerollTradesStatusPacket}: rerolls left on the villager behind one trade
 * menu, and the next one's price. Plain fields, no client classes, so the
 * packet handler can live in common code.
 */
public final class RerollTradesClientState {

    private record Status(int containerId, int remaining, int price) {}

    private static volatile Status status = new Status(-1, 0, 0);

    private RerollTradesClientState() {}

    static void set(int menuId, int left, int price) {
        status = new Status(menuId, Math.max(0, left), Math.max(0, price));
    }

    /** Rerolls left for trade menu {@code menuId}, or -1 when the server sent none (perk inactive). */
    public static int remainingFor(int menuId) {
        Status s = status;
        return s.containerId() == menuId ? s.remaining() : -1;
    }

    /** Emeralds the next reroll costs (0 = free), or -1 when the server sent no status for this menu. */
    public static int priceFor(int menuId) {
        Status s = status;
        return s.containerId() == menuId ? s.price() : -1;
    }

}
