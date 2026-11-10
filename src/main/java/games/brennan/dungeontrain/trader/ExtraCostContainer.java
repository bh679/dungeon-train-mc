package games.brennan.dungeontrain.trader;

import net.minecraft.world.SimpleContainer;

/** Lets {@code MerchantContainer} see its menu's extra-cost payments. Cast a {@code MerchantContainer} to this. */
public interface ExtraCostContainer {

    SimpleContainer dungeontrain$backing();

    void dungeontrain$setBacking(SimpleContainer backing);

    /** The index of the trade the player last selected. */
    int dungeontrain$selectionHint();
}
