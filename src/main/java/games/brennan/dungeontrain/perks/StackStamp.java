package games.brennan.dungeontrain.perks;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Shared by the perks that let an item stack higher by stamping a {@code MAX_STACK_SIZE} component on
 * it (Stack Potions, Stack Soups). Two stacks that differ only by that stamp are the same item for
 * merging; see {@code mixin.ItemStackPotionStackingMixin}.
 */
public final class StackStamp {

    /** The game's own ceiling: the max-stack-size component only encodes 1..99. Not a design cap. */
    public static final int MAX_STACK = 99;

    private StackStamp() {}

    /** Same item, and components that differ at most by the stack-size stamp. */
    public static boolean sameIgnoringStamp(ItemStack a, ItemStack b) {
        if (!a.is(b.getItem())) return false;
        return a.getComponentsPatch().forget(t -> t == DataComponents.MAX_STACK_SIZE)
            .equals(b.getComponentsPatch().forget(t -> t == DataComponents.MAX_STACK_SIZE));
    }
}
