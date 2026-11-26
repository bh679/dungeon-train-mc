package games.brennan.dungeontrain.perks.effects.alchemist;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's pocket brewing stand (Standless Brewing): fuel slot, ingredient slot, then any number of
 * bottle slots, plus the stand's fuel, brew progress and free-fuel timer. It lives on the player as a data
 * attachment ({@code ModDataAttachments.POCKET_BREWING}), saved with them.
 *
 * <p>Mutable on purpose: it is the {@link Container} the menu's slots edit in place, as a placed stand's
 * block entity is. Everything outside the menu changes it only through {@link PocketBrewing}'s tick.</p>
 */
public final class PocketBrewStand implements Container {

    public static final int FUEL_SLOT = 0;
    public static final int INGREDIENT_SLOT = 1;
    public static final int FIRST_BOTTLE = 2;

    public static final Codec<PocketBrewStand> CODEC = RecordCodecBuilder.create(i -> i.group(
        ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(PocketBrewStand::itemList),
        Codec.INT.optionalFieldOf("fuel", 0).forGetter(s -> s.fuel),
        Codec.INT.optionalFieldOf("brew_time", 0).forGetter(s -> s.brewTime),
        Codec.INT.optionalFieldOf("brew_total", 0).forGetter(s -> s.brewTotal),
        BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("brewing", Items.AIR).forGetter(s -> s.brewing),
        Codec.INT.optionalFieldOf("free_fuel_ticks", 0).forGetter(s -> s.freeFuelTicks)
    ).apply(i, PocketBrewStand::new));

    private NonNullList<ItemStack> items;
    int fuel;
    /** Ticks left on the brew in progress; 0 when idle. */
    int brewTime;
    /** The brew in progress's full length, for the screen's progress arrow. */
    int brewTotal;
    /** The ingredient the brew in progress started with (vanilla stops a brew when it is swapped). */
    Item brewing = Items.AIR;
    /** Ticks since the last free fuel. */
    int freeFuelTicks;

    public PocketBrewStand() {
        this(List.of(), 0, 0, 0, Items.AIR, 0);
    }

    private PocketBrewStand(List<ItemStack> saved, int fuel, int brewTime, int brewTotal, Item brewing, int freeFuelTicks) {
        this.items = NonNullList.withSize(Math.max(FIRST_BOTTLE, saved.size()), ItemStack.EMPTY);
        for (int i = 0; i < saved.size(); i++) items.set(i, saved.get(i));
        this.fuel = Math.max(0, fuel);
        this.brewTime = Math.max(0, brewTime);
        this.brewTotal = Math.max(0, brewTotal);
        this.brewing = brewing;
        this.freeFuelTicks = Math.max(0, freeFuelTicks);
    }

    private List<ItemStack> itemList() {
        List<ItemStack> out = new ArrayList<>(items);
        // trailing empty bottle slots need not be saved
        while (out.size() > FIRST_BOTTLE && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** Make room for {@code bottles} bottle slots; never drops anything (see {@link #takeBottlesPast}). */
    public void ensureBottles(int bottles) {
        int size = FIRST_BOTTLE + Math.max(0, bottles);
        if (items.size() >= size) return;
        NonNullList<ItemStack> grown = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < items.size(); i++) grown.set(i, items.get(i));
        items = grown;
    }

    /** Take out every bottle in a slot at or past {@code bottles}: a smaller rank no longer has those slots. */
    public List<ItemStack> takeBottlesPast(int bottles) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = FIRST_BOTTLE + Math.max(0, bottles); i < items.size(); i++) {
            if (!items.get(i).isEmpty()) out.add(items.set(i, ItemStack.EMPTY));
        }
        return out;
    }

    /** Take out everything, for a death drop; the stand is left empty and idle. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) out.add(items.set(i, ItemStack.EMPTY));
        }
        fuel = 0;
        brewTime = 0;
        brewTotal = 0;
        brewing = Items.AIR;
        freeFuelTicks = 0;
        return out;
    }

    /** A copy of this stand, for a player entity that replaces this one (respawn with keepInventory, End exit). */
    public PocketBrewStand copy() {
        List<ItemStack> copied = new ArrayList<>();
        items.forEach(s -> copied.add(s.copy()));
        return new PocketBrewStand(copied, fuel, brewTime, brewTotal, brewing, freeFuelTicks);
    }

    public int fuel() {
        return fuel;
    }

    public int brewTime() {
        return brewTime;
    }

    public int brewTotal() {
        return brewTotal;
    }

    public int freeFuelTicks() {
        return freeFuelTicks;
    }

    // ---- Container ---------------------------------------------------------------------------------

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= items.size() || items.get(slot).isEmpty() || amount <= 0) return ItemStack.EMPTY;
        return items.get(slot).split(amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= items.size()) return ItemStack.EMPTY;
        return items.set(slot, ItemStack.EMPTY);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0) return;
        if (slot >= items.size()) ensureBottles(slot - FIRST_BOTTLE + 1);
        items.set(slot, stack);
    }

    @Override
    public void setChanged() {
        // saved with the player; nothing to mark
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < items.size(); i++) items.set(i, ItemStack.EMPTY);
    }
}
