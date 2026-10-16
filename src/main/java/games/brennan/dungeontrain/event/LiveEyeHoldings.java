package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.item.LiveHeadpiecePendingTag;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Real Live Eyes a player holds, looking inside carried containers too: a Live Eye packed in a
 * shulker box (or a bundle) in the inventory or ender chest still counts. {@link #cap} walks the same
 * places and turns every Live Eye past the allowance into a TV, wherever it sits.
 */
public final class LiveEyeHoldings {

    /** Deepest nesting walked; vanilla stops shulkers inside shulkers, so 4 is generous. */
    private static final int MAX_DEPTH = 4;

    private LiveEyeHoldings() {}

    /** A resolved (not pending) Live Eye. */
    public static boolean isRealEye(ItemStack stack) {
        return stack.is(ModItems.LIVE_HEADPIECE.get()) && !LiveHeadpiecePendingTag.isPending(stack);
    }

    /** Real Live Eyes in {@code stack}: itself, plus any packed inside it. */
    public static int count(ItemStack stack) {
        return count(stack, 0);
    }

    /** Real Live Eyes in every slot of {@code container}, nested ones included. */
    public static int count(Container container) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) n += count(container.getItem(i));
        return n;
    }

    private static int count(ItemStack stack, int depth) {
        if (stack.isEmpty() || depth > MAX_DEPTH) return 0;
        int n = isRealEye(stack) ? 1 : 0;
        ItemContainerContents box = stack.get(DataComponents.CONTAINER);
        if (box != null) for (ItemStack inner : box.nonEmptyItems()) n += count(inner, depth + 1);
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStack inner : bundle.items()) n += count(inner, depth + 1);
        return n;
    }

    /**
     * Keep the first {@code allowance[0]} real Live Eyes met in slot {@code slot} of {@code container}
     * and turn the rest into {@code tv}, decrementing the allowance as eyes are kept. Returns how many
     * were converted.
     */
    public static int cap(Container container, int slot, int[] allowance, Supplier<ItemStack> tv) {
        ItemStack stack = container.getItem(slot);
        int[] converted = {0};
        ItemStack capped = cap(stack, allowance, tv, converted, 0);
        if (capped != stack) container.setItem(slot, capped);
        return converted[0];
    }

    /** The stack with extras turned to TVs: {@code stack} itself when nothing changed, else a new copy. */
    private static ItemStack cap(ItemStack stack, int[] allowance, Supplier<ItemStack> tv, int[] converted, int depth) {
        if (stack.isEmpty() || depth > MAX_DEPTH || count(stack, depth) == 0) return stack;
        if (isRealEye(stack)) {
            if (allowance[0] > 0) {
                allowance[0]--;
                return stack;
            }
            converted[0]++;
            return tv.get();
        }
        ItemStack out = stack;
        ItemContainerContents box = stack.get(DataComponents.CONTAINER);
        if (box != null) {
            List<ItemStack> items = new ArrayList<>();
            boolean changed = false;
            for (ItemStack inner : box.stream().toList()) {
                ItemStack next = cap(inner, allowance, tv, converted, depth + 1);
                changed |= next != inner;
                items.add(next == inner ? inner.copy() : next);
            }
            if (changed) {
                out = out.copy();
                out.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            }
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStack> items = new ArrayList<>();
            boolean changed = false;
            for (ItemStack inner : bundle.items()) {
                ItemStack next = cap(inner, allowance, tv, converted, depth + 1);
                changed |= next != inner;
                items.add(next == inner ? inner.copy() : next);
            }
            if (changed) {
                if (out == stack) out = out.copy();
                out.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
            }
        }
        return out;
    }
}
