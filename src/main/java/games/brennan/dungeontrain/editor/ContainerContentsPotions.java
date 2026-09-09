package games.brennan.dungeontrain.editor;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * The potion side of a {@link ContainerContentsEntry}: reading the vanilla potion off a stack
 * an author adds from their hand, and putting it back on the stack the roller spawns.
 *
 * <p>Nothing here randomises. A potion entry spawns exactly the potion it stores — Water,
 * Awkward, Healing, whatever was held — and an entry with no stored potion spawns vanilla's
 * "Uncraftable Potion". Random potions are the {@code dungeontrain:random_potion} /
 * {@code random_good_potion} / {@code random_bad_potion} placeholders, baked by
 * {@code ContainerContentsRoller}.</p>
 */
public final class ContainerContentsPotions {

    private ContainerContentsPotions() {}

    /** True for the three drinkable / splash / lingering potion item types. */
    public static boolean isPotionForm(Item item) {
        return item == Items.POTION
            || item == Items.SPLASH_POTION
            || item == Items.LINGERING_POTION;
    }

    /**
     * The vanilla potion registry id carried by {@code stack}, or {@code null} when the stack is
     * not a potion form or carries no named potion (vanilla's "Uncraftable Potion").
     */
    public static @Nullable ResourceLocation potionIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isPotionForm(stack.getItem())) return null;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return null;
        return contents.potion()
            .flatMap(Holder::unwrapKey)
            .map(ResourceKey::location)
            .orElse(null);
    }

    /**
     * Put the stored potion onto a freshly built stack. No-op when {@code potionId} is null,
     * the item is not a potion form, or the id does not resolve in {@code registries} (a
     * stripped or modded registry leaves the bottle as-is rather than crashing the roll).
     */
    public static void applyStoredPotion(ItemStack stack, @Nullable ResourceLocation potionId,
                                         HolderLookup.Provider registries) {
        if (potionId == null || stack.isEmpty() || !isPotionForm(stack.getItem())) return;
        Optional<HolderLookup.RegistryLookup<Potion>> lookup = registries.lookup(Registries.POTION);
        if (lookup.isEmpty()) return;
        lookup.get().get(ResourceKey.create(Registries.POTION, potionId))
            .ifPresent(holder -> stack.set(DataComponents.POTION_CONTENTS, new PotionContents(holder)));
    }
}
