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
 */
public record ContainerContentsEntry(ResourceLocation itemId, int count, int weight) {

    public static final ResourceLocation AIR_ID = new ResourceLocation("minecraft", "air");

    public ContainerContentsEntry {
        if (itemId == null) throw new IllegalArgumentException("itemId");
        if (count < 1) count = 1;
        if (count > 64) count = 64;
        if (weight < 1) weight = 1;
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
        return new ContainerContentsEntry(itemId, count, newWeight);
    }

    public ContainerContentsEntry withCount(int newCount) {
        return new ContainerContentsEntry(itemId, newCount, weight);
    }
}
