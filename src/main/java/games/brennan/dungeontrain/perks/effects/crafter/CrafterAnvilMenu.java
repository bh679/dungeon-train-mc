package games.brennan.dungeontrain.perks.effects.crafter;

/**
 * Duck interface the {@code AnvilMenu} mixin implements: whether this anvil ignores vanilla's
 * "Too Expensive!" cap at 40 levels. Decided on the server from the player's Enchant Cost rank and
 * synced to the client through a menu data slot, so the anvil screen shows a price instead; and the
 * pre-discount price, which the screen shows struck through.
 */
public interface CrafterAnvilMenu {

    boolean dungeontrain$uncapped();

    /** Vanilla's price before Enchant Cost lowered it, or 0 when it was not lowered. Server-set, synced. */
    int dungeontrain$originalCost();
}
