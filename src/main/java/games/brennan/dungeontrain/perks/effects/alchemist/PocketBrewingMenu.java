package games.brennan.dungeontrain.perks.effects.alchemist;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModMenuTypes;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.Optional;

/**
 * The pocket brewing stand's menu: fuel and ingredient slots as vanilla's stand, then the stand's bottle
 * slots in centred rows of {@value #COLUMNS}, then the player inventory. More than {@value #PAGE_ROWS} rows
 * pages: the screen shows one page of bottles at a time ({@link #setPage}).
 */
public final class PocketBrewingMenu extends AbstractContainerMenu {

    public static final int COLUMNS = 9;
    public static final int PAGE_ROWS = 6;
    public static final int PAGE_SIZE = COLUMNS * PAGE_ROWS;
    public static final int FUEL_X = 17, FUEL_Y = 17;
    public static final int INGREDIENT_X = 79, INGREDIENT_Y = 17;
    public static final int BOTTLES_Y = 51;
    public static final int SLOT = 18;
    public static final int WIDTH = 176;

    static final int DATA_FUEL = 0, DATA_BREW_TIME = 1, DATA_BREW_TOTAL = 2, DATA_REFUEL_SECONDS = 3, DATA_COUNT = 4;

    private final Container stand;
    private final ContainerData data;
    private final int bottles;
    private final Player player;
    private final PotionBrewing brewing;
    private final int invTop;
    private int page;

    public PocketBrewingMenu(int containerId, Inventory inventory, Container stand, int bottles, ContainerData data) {
        super(ModMenuTypes.POCKET_BREWING.get(), containerId);
        this.stand = stand;
        this.data = data;
        this.bottles = Math.max(1, bottles);
        this.player = inventory.player;
        this.brewing = inventory.player.level().potionBrewing();

        addSlot(new Slot(stand, PocketBrewStand.FUEL_SLOT, FUEL_X, FUEL_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.BLAZE_POWDER);
            }
        });
        addSlot(new Slot(stand, PocketBrewStand.INGREDIENT_SLOT, INGREDIENT_X, INGREDIENT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return brewing.isIngredient(stack);
            }
        });
        for (int i = 0; i < this.bottles; i++) {
            addSlot(new BottleSlot(i, bottleX(i, this.bottles), bottleY(i)));
        }
        addDataSlots(data);

        invTop = inventoryTop(this.bottles);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * SLOT, invTop + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * SLOT, invTop + 58));
        }
    }

    /** Client side: rebuilt from the open packet with a stand-in stand. */
    public static PocketBrewingMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        int bottles = Math.max(1, buf.readVarInt());
        PocketBrewStand standIn = new PocketBrewStand();
        standIn.ensureBottles(bottles);
        return new PocketBrewingMenu(containerId, inventory, standIn, bottles, new SimpleContainerData(DATA_COUNT));
    }

    /** The server's live numbers for the screen: fuel, brew progress, seconds to the next refuel. */
    static ContainerData data(ServerPlayer player, PocketBrewStand stand) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_FUEL -> stand.fuel();
                    case DATA_BREW_TIME -> stand.brewTime();
                    case DATA_BREW_TOTAL -> stand.brewTotal();
                    case DATA_REFUEL_SECONDS -> {
                        if (stand.fuel() >= PocketBrewingMath.FUEL_MAX) yield -1;
                        int rank = Perks.rank(player, AlchemistPerks.STANDLESS_BREWING);
                        int left = PocketBrewingMath.refuelTicks(rank) - stand.refuelTicks();
                        yield Math.max(0, (left + 19) / 20);
                    }
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {}

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    // ---- layout ------------------------------------------------------------------------------------

    public static int rows(int bottles) {
        return (Math.max(1, bottles) + COLUMNS - 1) / COLUMNS;
    }

    public static int visibleRows(int bottles) {
        return Math.min(PAGE_ROWS, rows(bottles));
    }

    public static int pages(int bottles) {
        return (Math.max(1, bottles) + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /** Bottle {@code i}'s x: rows are centred, so a short last row sits in the middle. */
    public static int bottleX(int i, int bottles) {
        int inPage = i % PAGE_SIZE;
        int rowStart = i - inPage + (inPage / COLUMNS) * COLUMNS;
        int inRow = Math.min(COLUMNS, bottles - rowStart);
        return 8 + (COLUMNS * SLOT - inRow * SLOT) / 2 + (inPage % COLUMNS) * SLOT;
    }

    public static int bottleY(int i) {
        return BOTTLES_Y + ((i % PAGE_SIZE) / COLUMNS) * SLOT;
    }

    public static int inventoryTop(int bottles) {
        return BOTTLES_Y + visibleRows(bottles) * SLOT + 15;
    }

    public int bottles() {
        return bottles;
    }

    public int inventoryTop() {
        return invTop;
    }

    public int page() {
        return page;
    }

    /** Client only: which page of bottles the screen shows. */
    public void setPage(int page) {
        this.page = Math.max(0, Math.min(pages(bottles) - 1, page));
    }

    public int fuel() {
        return data.get(DATA_FUEL);
    }

    public int brewTime() {
        return data.get(DATA_BREW_TIME);
    }

    public int brewTotal() {
        return data.get(DATA_BREW_TOTAL);
    }

    /** Seconds to the next refuel; -1 with a full bar. */
    public int refuelSeconds() {
        return data.get(DATA_REFUEL_SECONDS);
    }

    // ---- behaviour ---------------------------------------------------------------------------------

    @Override
    public boolean stillValid(Player p) {
        return p.isAlive() && Perks.rank(p, AlchemistPerks.STANDLESS_BREWING) > 0
            && (p.level().isClientSide || p.hasData(ModDataAttachments.POCKET_BREWING.get()));
    }

    private boolean isBottle(ItemStack stack) {
        return brewing.isInput(stack) || stack.is(Items.GLASS_BOTTLE);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        int standEnd = 2 + bottles;
        int invEnd = standEnd + 27;
        int hotbarEnd = invEnd + 9;
        if (index < standEnd) {
            if (!moveItemStackTo(stack, standEnd, hotbarEnd, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(stack, copy);
        } else if (stack.is(Items.BLAZE_POWDER)) {
            if (!moveItemStackTo(stack, 0, 1, false)
                && !(slots.get(1).mayPlace(stack) && moveItemStackTo(stack, 1, 2, false))) return ItemStack.EMPTY;
        } else if (slots.get(1).mayPlace(stack)) {
            if (!moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
        } else if (isBottle(copy)) {
            if (!moveItemStackTo(stack, 2, standEnd, false)) return ItemStack.EMPTY;
        } else if (index < invEnd) {
            if (!moveItemStackTo(stack, invEnd, hotbarEnd, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, standEnd, invEnd, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) return ItemStack.EMPTY;
        slot.onTake(p, stack);
        return copy;
    }

    /** One bottle per slot, as vanilla's stand; taking a brewed potion counts as brewing it. */
    private final class BottleSlot extends Slot {
        private final int bottle;

        BottleSlot(int bottle, int x, int y) {
            super(stand, PocketBrewStand.FIRST_BOTTLE + bottle, x, y);
            this.bottle = bottle;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isBottle(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean isActive() {
            return bottle / PAGE_SIZE == page;
        }

        @Override
        public void onTake(Player p, ItemStack stack) {
            Optional<Holder<Potion>> potion = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion();
            if (potion.isPresent() && p instanceof ServerPlayer sp) {
                net.neoforged.neoforge.event.EventHooks.onPlayerBrewedPotion(p, stack);
                CriteriaTriggers.BREWED_POTION.trigger(sp, potion.get());
            }
            super.onTake(p, stack);
        }
    }
}
