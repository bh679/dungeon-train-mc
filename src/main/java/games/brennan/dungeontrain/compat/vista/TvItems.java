package games.brennan.dungeontrain.compat.vista;

import games.brennan.dungeontrain.registry.ModDataComponents;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A Vista TV as an item in the hand: a portable set. Left-click switches it on and off
 * ({@code client/live/HeldTvToggleClient} → {@code net/TvHeldTogglePacket}); while on, the live feed
 * plays on its screen face ({@code mixin/vista/TvItemRendererMixin} → {@code client/live/HeldTvScreen}),
 * and placing it puts the block down switched on ({@code mixin/vista/TVBlockMixin}).
 *
 * <p>The on/off state is the {@link ModDataComponents#TV_ON} component; {@link #withOn} returns a
 * copy, the given stack is never changed.</p>
 */
public final class TvItems {

    private TvItems() {}

    /** Any Vista TV item (the block behind it is a {@link TVBlock}). */
    public static boolean isTv(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof TVBlock;
    }

    public static boolean isOn(ItemStack stack) {
        return isTv(stack) && Boolean.TRUE.equals(stack.get(ModDataComponents.TV_ON.get()));
    }

    /** A copy of {@code stack} switched {@code on}; off is the absence of the component. */
    public static ItemStack withOn(ItemStack stack, boolean on) {
        ItemStack copy = stack.copy();
        if (on) copy.set(ModDataComponents.TV_ON.get(), Boolean.TRUE);
        else copy.remove(ModDataComponents.TV_ON.get());
        return copy;
    }

    /**
     * Which hand a left-click toggles, or null when the click is an ordinary attack: the main hand
     * when it holds a TV; the offhand when the main hand is empty and the offhand holds one. A sword
     * in the main hand with a TV in the offhand still swings the sword.
     */
    @Nullable
    public static InteractionHand toggleHand(ItemStack main, ItemStack off) {
        return toggleHand(isTv(main), main.isEmpty(), isTv(off));
    }

    /** The rule of {@link #toggleHand(ItemStack, ItemStack)} on bare facts, for tests. */
    @Nullable
    static InteractionHand toggleHand(boolean mainIsTv, boolean mainEmpty, boolean offIsTv) {
        if (mainIsTv) return InteractionHand.MAIN_HAND;
        if (mainEmpty && offIsTv) return InteractionHand.OFF_HAND;
        return null;
    }
}
