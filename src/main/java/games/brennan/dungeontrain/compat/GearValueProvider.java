package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.GearValueFormula.Reference;
import games.brennan.dungeontrain.compat.GearValueFormula.Slot;
import games.brennan.dungeontrain.compat.GearValueFormula.Stats;
import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.AbstractMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Trade Everything value provider for swords, tools, armour and enchanted books, priced by
 * {@link GearValueFormula} from the stack's own stats and enchantments. Everything else returns
 * empty and falls through to Trade Everything's recipe valuation.
 *
 * <p>Each slot is scored against its iron item, whose price is asked of Trade Everything itself —
 * so iron gear keeps the value it has today. That nested ask runs every provider again, this one
 * included; {@link #IN_REFERENCE} makes it step aside so the iron item is priced by recipe.</p>
 */
public final class GearValueProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Values above this are clamped — a corrupt stack must not overflow Trade Everything's arithmetic. */
    private static final int MAX_SIXTEENTHS = 1_000_000;

    private static final Map<Slot, Item> IRON = new EnumMap<>(Map.of(
        Slot.SWORD, Items.IRON_SWORD, Slot.AXE, Items.IRON_AXE, Slot.PICKAXE, Items.IRON_PICKAXE,
        Slot.SHOVEL, Items.IRON_SHOVEL, Slot.HOE, Items.IRON_HOE, Slot.HELMET, Items.IRON_HELMET,
        Slot.CHESTPLATE, Items.IRON_CHESTPLATE, Slot.LEGGINGS, Items.IRON_LEGGINGS, Slot.BOOTS, Items.IRON_BOOTS));
    private static final Map<Slot, Item> DIAMOND = new EnumMap<>(Map.of(
        Slot.SWORD, Items.DIAMOND_SWORD, Slot.AXE, Items.DIAMOND_AXE, Slot.PICKAXE, Items.DIAMOND_PICKAXE,
        Slot.SHOVEL, Items.DIAMOND_SHOVEL, Slot.HOE, Items.DIAMOND_HOE, Slot.HELMET, Items.DIAMOND_HELMET,
        Slot.CHESTPLATE, Items.DIAMOND_CHESTPLATE, Slot.LEGGINGS, Items.DIAMOND_LEGGINGS, Slot.BOOTS, Items.DIAMOND_BOOTS));
    private static final Map<Slot, Item> NETHERITE = new EnumMap<>(Map.of(
        Slot.SWORD, Items.NETHERITE_SWORD, Slot.AXE, Items.NETHERITE_AXE, Slot.PICKAXE, Items.NETHERITE_PICKAXE,
        Slot.SHOVEL, Items.NETHERITE_SHOVEL, Slot.HOE, Items.NETHERITE_HOE, Slot.HELMET, Items.NETHERITE_HELMET,
        Slot.CHESTPLATE, Items.NETHERITE_CHESTPLATE, Slot.LEGGINGS, Items.NETHERITE_LEGGINGS, Slot.BOOTS, Items.NETHERITE_BOOTS));

    /** True while this thread asks Trade Everything for an iron reference price. */
    private static final ThreadLocal<Boolean> IN_REFERENCE = ThreadLocal.withInitial(() -> false);

    private static volatile boolean warned = false;

    private GearValueProvider() {}

    /** The provider registered with Trade Everything, in sixteenths of an emerald. */
    public static OptionalInt value(ItemStack stack) {
        if (stack.isEmpty() || IN_REFERENCE.get()) return OptionalInt.empty();
        try {
            return emeralds(stack).map(GearValueProvider::toSixteenths).map(OptionalInt::of).orElse(OptionalInt.empty());
        } catch (RuntimeException e) {
            // A throw would make Trade Everything drop this provider for the session; defer instead.
            if (!warned) {
                warned = true;
                LOGGER.warn("[trade-values] gear valuation failed for {}; using Trade Everything's default", stack, e);
            }
            return OptionalInt.empty();
        }
    }

    /** The stack's value in emeralds, or empty when it isn't priced gear or an enchanted book. */
    public static Optional<Double> emeralds(ItemStack stack) {
        if (stack.is(Items.ENCHANTED_BOOK)) {
            return Optional.of(GearValueFormula.bookEmeralds(GearStats.enchantments(stack), GearValueProvider::netherite));
        }
        return GearStats.slotOf(stack).map(slot ->
            GearValueFormula.emeralds(GearStats.read(stack, slot), reference(slot), GearStats.enchantments(stack)));
    }

    private static Reference reference(Slot slot) {
        Stats iron = GearStats.read(new ItemStack(IRON.get(slot)), slot);
        Stats diamond = GearStats.read(new ItemStack(DIAMOND.get(slot)), slot);
        return new Reference(iron, diamond, ironEmeralds(slot));
    }

    private static Map.Entry<Stats, Reference> netherite(Slot slot) {
        Stats stats = GearStats.read(new ItemStack(NETHERITE.get(slot)), slot);
        return new AbstractMap.SimpleImmutableEntry<>(stats, reference(slot));
    }

    private static double ironEmeralds(Slot slot) {
        IN_REFERENCE.set(true);
        try {
            return TradeEverythingApi.getValueSixteenths(new ItemStack(IRON.get(slot))) / 16.0;
        } finally {
            IN_REFERENCE.set(false);
        }
    }

    private static int toSixteenths(double emeralds) {
        if (!Double.isFinite(emeralds)) return MAX_SIXTEENTHS;
        return (int) Math.max(1, Math.min(MAX_SIXTEENTHS, Math.round(emeralds * 16)));
    }
}
