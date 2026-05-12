package games.brennan.dungeontrain.editor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * One entry in a {@link ContainerContentsPool} — a weighted item that may
 * spawn in a slot of a chest / barrel / dispenser / any block whose
 * BlockEntity implements {@link net.minecraft.world.Container}.
 *
 * <p>Identity is by {@link ResourceLocation} so the wire / disk format is
 * stable across Item-instance reloads. {@code count} is the stack size when
 * this entry rolls; {@code weight} is the proportional roll weight (≥ 1).</p>
 *
 * <p>{@link #AIR_ID} is the empty-slot sentinel: an entry with this id rolls
 * to "leave the slot empty," giving authors a way to control fill density
 * without a separate parameter.</p>
 *
 * <p>{@code randomDurability} / {@code randomEnchantment} are master toggles
 * that allow the roller to apply random damage / random enchantments to the
 * spawned stack. {@code durabilityChance} / {@code enchantmentChance} are the
 * per-roll probabilities (0-100) that the effect actually applies on a given
 * spawn — so a value of 50 means "half the spawns get the effect."</p>
 */
public record ContainerContentsEntry(
    ResourceLocation itemId,
    int count,
    int weight,
    boolean randomDurability,
    int durabilityChance,
    boolean randomEnchantment,
    int enchantmentChance
) {

    public static final ResourceLocation AIR_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    public static final boolean DEFAULT_RANDOM_DURABILITY = true;
    public static final int DEFAULT_DURABILITY_CHANCE = 100;
    public static final boolean DEFAULT_RANDOM_ENCHANTMENT = true;
    public static final int DEFAULT_ENCHANTMENT_CHANCE = 25;

    public ContainerContentsEntry {
        if (itemId == null) throw new IllegalArgumentException("itemId");
        if (count < 1) count = 1;
        if (count > 64) count = 64;
        if (weight < 1) weight = 1;
        if (durabilityChance < 0) durabilityChance = 0;
        if (durabilityChance > 100) durabilityChance = 100;
        if (enchantmentChance < 0) enchantmentChance = 0;
        if (enchantmentChance > 100) enchantmentChance = 100;
    }

    /** Convenience constructor — supplies the four new fields with defaults. */
    public ContainerContentsEntry(ResourceLocation itemId, int count, int weight) {
        this(itemId, count, weight,
            DEFAULT_RANDOM_DURABILITY, DEFAULT_DURABILITY_CHANCE,
            DEFAULT_RANDOM_ENCHANTMENT, DEFAULT_ENCHANTMENT_CHANCE);
    }

    public static ContainerContentsEntry of(Item item, int count, int weight) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return new ContainerContentsEntry(id, count, weight);
    }

    public static ContainerContentsEntry air(int weight) {
        return new ContainerContentsEntry(AIR_ID, 1, weight);
    }

    public boolean isAir() {
        return AIR_ID.equals(itemId);
    }

    /** Resolve the registered Item, falling back to AIR if the id is unknown. */
    public Item resolveItem() {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        return item == null ? Items.AIR : item;
    }

    public ContainerContentsEntry withWeight(int newWeight) {
        return new ContainerContentsEntry(itemId, count, newWeight,
            randomDurability, durabilityChance, randomEnchantment, enchantmentChance);
    }

    public ContainerContentsEntry withCount(int newCount) {
        return new ContainerContentsEntry(itemId, newCount, weight,
            randomDurability, durabilityChance, randomEnchantment, enchantmentChance);
    }

    public ContainerContentsEntry withRandomDurability(boolean v) {
        return new ContainerContentsEntry(itemId, count, weight,
            v, durabilityChance, randomEnchantment, enchantmentChance);
    }

    public ContainerContentsEntry withDurabilityChance(int v) {
        return new ContainerContentsEntry(itemId, count, weight,
            randomDurability, v, randomEnchantment, enchantmentChance);
    }

    public ContainerContentsEntry withRandomEnchantment(boolean v) {
        return new ContainerContentsEntry(itemId, count, weight,
            randomDurability, durabilityChance, v, enchantmentChance);
    }

    public ContainerContentsEntry withEnchantmentChance(int v) {
        return new ContainerContentsEntry(itemId, count, weight,
            randomDurability, durabilityChance, randomEnchantment, v);
    }
}
