package games.brennan.dungeontrain.perks.effects.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.registry.ModMenuTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.slf4j.Logger;

/**
 * The container menu Drift Gift Repaid opens instead of the vanilla one on a drifting carriage's
 * storage: the real container in vanilla's 9-wide chest layout, the player inventory where
 * {@code ChestMenu} puts it, and one extra <b>gift slot</b> outside the window. A stack placed in the
 * gift slot is moved into the container (as much as fits), marked {@link DriftGiftTag paid}, and the
 * player is paid {@link DriftGiftPayout} sixteenths per item in emeralds; what does not fit stays in the
 * gift slot and is handed back when the menu closes.
 *
 * <p>Taking a paid stack back out charges the payer the same sixteenths (ledger first, then emeralds
 * from the inventory), and the slot refuses the pickup when they cannot cover it. Someone else's paid
 * gift is free to take and loses its mark. The existing drifting-carriage close hook
 * ({@code SharedCarriageAdvancementEvents.onContainerClose}) still sees the contents go up, so the
 * gift uploads and the gift advancement fires as it would from a vanilla chest.</p>
 */
public final class DriftGiftMenu extends AbstractContainerMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String KEY_SLOT_TOOLTIP = "gui.dungeontrain.drift_gift.slot";
    public static final String KEY_PAID = "message.dungeontrain.drift_gift.paid";
    public static final String KEY_BANKED = "message.dungeontrain.drift_gift.banked";
    public static final String KEY_CHARGED = "message.dungeontrain.drift_gift.charged";
    public static final String KEY_CANNOT_AFFORD = "message.dungeontrain.drift_gift.cannot_afford";

    /** Vanilla chest window width; the gift slot sits just right of it. */
    public static final int WINDOW_WIDTH = 176;
    public static final int GIFT_SLOT_X = WINDOW_WIDTH + 7;
    public static final int GIFT_SLOT_Y = 18;
    public static final int MAX_ROWS = 6;

    private final Container container;
    private final SimpleContainer gift = new SimpleContainer(1);
    private final int rows;
    private final int rank;
    private final Player player;
    private boolean processing = false;

    private DriftGiftMenu(int containerId, Inventory inventory, Container container, int rows, int rank) {
        super(ModMenuTypes.DRIFT_GIFT.get(), containerId);
        checkContainerSize(container, rows * 9);
        this.container = container;
        this.rows = rows;
        this.rank = rank;
        this.player = inventory.player;
        container.startOpen(inventory.player);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new ContainerSlot(container, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }
        addSlot(new GiftSlot(gift, 0, GIFT_SLOT_X, GIFT_SLOT_Y));
        int rowsOffset = (rows - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + rowsOffset));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 161 + rowsOffset));
        }
    }

    /** Client side: rebuilt from the open-screen data with a stand-in container. */
    public static DriftGiftMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        int rows = Math.clamp(buf.readVarInt(), 1, MAX_ROWS);
        int rank = buf.readVarInt();
        return new DriftGiftMenu(containerId, inventory, new SimpleContainer(rows * 9), rows, rank);
    }

    /** Open the menu over {@code container} for {@code player}. {@code container.getContainerSize()} must be 9..54, a multiple of 9. */
    public static void open(ServerPlayer player, Container container, Component title, int rank) {
        int rows = container.getContainerSize() / 9;
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new DriftGiftMenu(id, inv, container, rows, rank), title),
            buf -> {
                buf.writeVarInt(rows);
                buf.writeVarInt(rank);
            });
    }

    /** Whether a container of this size can be shown: vanilla's 9-wide layout, up to six rows. */
    public static boolean supports(int containerSize) {
        return containerSize >= 9 && containerSize <= MAX_ROWS * 9 && containerSize % 9 == 0;
    }

    public int rows() {
        return rows;
    }

    public int rank() {
        return rank;
    }

    /** Payout percentage, for the tooltip. */
    public int ratePercent() {
        return DriftGiftPayout.ratePercent(rank);
    }

    public int giftSlotIndex() {
        return rows * 9;
    }

    private int containerSlots() {
        return rows * 9;
    }

    private int inventoryStart() {
        return containerSlots() + 1;
    }

    // ---------------- The gift ----------------

    /** Move whatever sits in the gift slot into the container and pay for it. Server only. */
    private void processGift() {
        if (processing || player.level().isClientSide() || !(player instanceof ServerPlayer sp)) return;
        ItemStack offered = gift.getItem(0);
        if (offered.isEmpty()) return;
        processing = true;
        try {
            int perItem = DriftGiftPayout.perItem(DriftGiftValue.sixteenthsPerItem(offered), rank);
            ItemStack toInsert = offered.copy();
            if (perItem > 0) DriftGiftTag.apply(toInsert, DriftGiftTag.of(sp.getUUID(), perItem));
            int before = toInsert.getCount();
            ItemStack left = HopperBlockEntity.addItem(null, container, toInsert, null);
            int moved = before - left.getCount();
            if (moved <= 0) return; // no room: the stack stays in the gift slot for the player to take back
            gift.setItem(0, left.isEmpty() ? ItemStack.EMPTY : offered.copyWithCount(left.getCount()));
            container.setChanged();
            pay(sp, (long) perItem * moved);
            LOGGER.debug("[DungeonTrain] drift gift: {} x{} by {} -> {} sixteenths/item (rank {})",
                    offered.getItem(), moved, sp.getGameProfile().getName(), perItem, rank);
        } finally {
            processing = false;
        }
    }

    private static void pay(ServerPlayer sp, long sixteenths) {
        if (sixteenths <= 0) return;
        DriftGiftLedger.Move m = DriftGiftLedger.credit(DriftGiftLedger.balance(sp.getUUID()), sixteenths);
        DriftGiftLedger.setBalance(sp.getUUID(), m.balance());
        if (m.emeralds() > 0) {
            giveEmeralds(sp, m.emeralds());
            sp.displayClientMessage(Component.translatable(KEY_PAID, m.emeralds()), true);
        } else {
            sp.displayClientMessage(Component.translatable(KEY_BANKED, m.balance(), DriftGiftPayout.SIXTEENTHS_PER_EMERALD), true);
        }
    }

    private static void giveEmeralds(ServerPlayer sp, int count) {
        ItemStack emeralds = new ItemStack(Items.EMERALD, count);
        if (!sp.getInventory().add(emeralds) && !emeralds.isEmpty()) sp.drop(emeralds, false);
    }

    // ---------------- Taking a paid gift back ----------------

    /** Emeralds the player holds, for the affordability check. */
    static int emeraldsHeld(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(Items.EMERALD)) n += s.getCount();
        for (ItemStack s : p.getInventory().offhand) if (s.is(Items.EMERALD)) n += s.getCount();
        return n;
    }

    static boolean canRepay(Player p, long sixteenths) {
        return DriftGiftLedger.canCharge(DriftGiftLedger.balance(p.getUUID()), sixteenths, emeraldsHeld(p));
    }

    /** Charge the payer {@code sixteenths}: ledger first, then emeralds. Caller checked {@link #canRepay}. */
    static void chargeBack(Player p, long sixteenths) {
        if (sixteenths <= 0) return;
        DriftGiftLedger.Move m = DriftGiftLedger.charge(DriftGiftLedger.balance(p.getUUID()), sixteenths);
        DriftGiftLedger.setBalance(p.getUUID(), m.balance());
        int toRemove = m.emeralds();
        if (toRemove > 0) {
            toRemove -= removeEmeralds(p.getInventory().items, toRemove);
            if (toRemove > 0) removeEmeralds(p.getInventory().offhand, toRemove);
            if (p instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.translatable(KEY_CHARGED, m.emeralds()), true);
            }
        }
    }

    private static int removeEmeralds(java.util.List<ItemStack> stacks, int count) {
        int removed = 0;
        for (ItemStack s : stacks) {
            if (removed >= count || !s.is(Items.EMERALD)) continue;
            int take = Math.min(count - removed, s.getCount());
            s.shrink(take);
            removed += take;
        }
        return removed;
    }

    /**
     * Settle the mark on a stack the player is taking: charge the payer (and refuse if they cannot
     * cover it), clear it for anyone. Returns false when the take must be refused.
     */
    static boolean settleTake(Player p, ItemStack taken) {
        DriftGiftTag tag = DriftGiftTag.of(taken);
        if (tag == null) return true;
        if (tag.paidTo(p.getUUID())) {
            long charge = tag.chargeFor(taken.getCount());
            if (!canRepay(p, charge)) {
                if (p instanceof ServerPlayer sp) sp.displayClientMessage(Component.translatable(KEY_CANNOT_AFFORD), true);
                return false;
            }
            chargeBack(p, charge);
        }
        DriftGiftTag.clear(taken);
        return true;
    }

    // ---------------- Menu plumbing ----------------

    @Override
    public boolean stillValid(Player p) {
        return container.stillValid(p);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack moving = slot.getItem();
        int chest = containerSlots();
        if (index < chest) return quickMoveOutOfContainer(p, slot, moving);
        if (index == giftSlotIndex()) {
            ItemStack result = moving.copy();
            if (!moveItemStackTo(moving, inventoryStart(), slots.size(), true)) return ItemStack.EMPTY;
            slot.setChanged();
            return result;
        }
        // From the player inventory into the container, as a vanilla chest does. Not via the gift slot:
        // paying is the deliberate drop into that one slot.
        ItemStack result = moving.copy();
        if (!moveItemStackTo(moving, 0, chest, false)) return ItemStack.EMPTY;
        if (moving.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return result;
    }

    /**
     * Shift-click out of the container: a paid stack is charged for what actually moves and loses its
     * mark on the way (vanilla's quick-move never calls {@code onTake}, so it is settled here).
     */
    private ItemStack quickMoveOutOfContainer(Player p, Slot slot, ItemStack moving) {
        DriftGiftTag tag = DriftGiftTag.of(moving);
        int before = moving.getCount();
        if (tag != null && tag.paidTo(p.getUUID()) && !canRepay(p, tag.chargeFor(before))) {
            if (p instanceof ServerPlayer sp) sp.displayClientMessage(Component.translatable(KEY_CANNOT_AFFORD), true);
            return ItemStack.EMPTY;
        }
        ItemStack stripped = moving.copy();
        DriftGiftTag.clear(stripped);
        if (!moveItemStackTo(stripped, inventoryStart(), slots.size(), true)) return ItemStack.EMPTY;
        int moved = before - stripped.getCount();
        if (moved <= 0) return ItemStack.EMPTY;
        if (tag != null && tag.paidTo(p.getUUID())) chargeBack(p, tag.chargeFor(moved));
        if (stripped.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            if (tag != null) DriftGiftTag.apply(stripped, tag);
            slot.set(stripped);
        }
        return moving.copy();
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        container.stopOpen(p);
        clearContainer(p, gift); // whatever did not fit goes back to the player, never lost
    }

    /** A slot over the real container that settles paid marks on pickup. */
    private final class ContainerSlot extends Slot {
        ContainerSlot(Container c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override
        public boolean mayPickup(Player p) {
            DriftGiftTag tag = DriftGiftTag.of(getItem());
            if (tag == null || !tag.paidTo(p.getUUID())) return true;
            return canRepay(p, tag.chargeFor(getItem().getCount()));
        }

        @Override
        public void onTake(Player p, ItemStack taken) {
            settleTake(p, taken);
            super.onTake(p, taken);
        }
    }

    /** The one slot that pays. Anything set into it is processed at once. */
    private final class GiftSlot extends Slot {
        GiftSlot(Container c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override
        public void set(ItemStack stack) {
            super.set(stack);
            processGift();
        }

        @Override
        public void setChanged() {
            super.setChanged();
            processGift();
        }
    }
}
