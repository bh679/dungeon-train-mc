package games.brennan.dungeontrain.perks.effects.crafter;

/**
 * Duck interface the {@code AnvilMenu} mixin implements: whether the current combine gets past
 * vanilla's "Too Expensive!" cap at 40 levels (only when the Enchant Cost discount brings a 40+ price
 * under 40). Decided on the server per combine and synced to the client through a menu data slot, so
 * the anvil screen shows the price instead; and the
 * pre-discount price, which the screen shows struck through.
 */
public interface CrafterAnvilMenu {

    boolean dungeontrain$uncapped();

    /** Vanilla's price before Enchant Cost lowered it, or 0 when it was not lowered. Server-set, synced. */
    int dungeontrain$originalCost();
}
