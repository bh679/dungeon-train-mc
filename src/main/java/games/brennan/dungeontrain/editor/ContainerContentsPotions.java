package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.registry.ModItems;
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
import java.util.Set;

/**
 * The potion side of a {@link ContainerContentsEntry}: reading the vanilla potion off a stack
 * an author adds from their hand, putting it back on the stack the roller spawns, and the
 * rule for which potion entries the roller randomises.
 *
 * <p>The rule: a potion with a real effect (Healing, Poison, ...) is placed exactly as
 * authored. An effectless base — mundane, thick, awkward, water — or a bottle with no potion
 * at all is randomised into a tiered potion of random form, as is the explicit
 * {@code dungeontrain:random_potion} placeholder. The baking itself lives in
 * {@code ContainerContentsRoller}.</p>
 */
public final class ContainerContentsPotions {

    private ContainerContentsPotions() {}

    /** Effectless vanilla bases that a loot entry treats as "give me a random potion". */
    private static final Set<ResourceLocation> RANDOMISED_BASES = Set.of(
        ResourceLocation.withDefaultNamespace("water"),
        ResourceLocation.withDefaultNamespace("mundane"),
        ResourceLocation.withDefaultNamespace("thick"),
        ResourceLocation.withDefaultNamespace("awkward"));

    /**
     * True when a potion entry storing {@code potionId} should be randomised: no potion at
     * all (an empty "Uncraftable" bottle, or an entry added by id alone) or one of the
     * effectless bases. Any other potion is kept as authored.
     */
    public static boolean isRandomisedBase(@Nullable ResourceLocation potionId) {
        return potionId == null || RANDOMISED_BASES.contains(potionId);
    }

    /**
     * True when the rolled entry becomes a random potion: the
     * {@code dungeontrain:random_potion} placeholder, or a potion-form item whose stored
     * potion {@link #isRandomisedBase} accepts.
     */
    public static boolean isRandomisedEntry(Item item, @Nullable ResourceLocation potionId) {
        if (item == ModItems.RANDOM_POTION.get()) return true;
        return isPotionForm(item) && isRandomisedBase(potionId);
    }

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
