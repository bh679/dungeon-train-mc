package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.registry.ModMenuTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Ender Chest menu a Free Play run opens: the player's live chest laid out per
 * {@link EnderChestLayout} — vanilla's 9×3, or the expanded 19×6 with its wings — plus the player
 * inventory in vanilla's chest position, and two menu buttons: expand, and shrink.
 * See {@link EnderChestExpansion}.
 *
 * <p>Why not vanilla's {@code ChestMenu}: its slots are a 9-wide grid and there is no menu type for
 * anything else; the wings live outside the window. The slot behaviour itself — shift-click, drag,
 * {@code stillValid}, open/close bookkeeping — is {@code ChestMenu}'s, reproduced. Which layout the
 * client should build, and which button (if any) applies, travel in the open-screen packet's extra
 * data ({@link #writeExtra}/{@link #fromNetwork}) — no packet of DT's own. The buttons ride vanilla's
 * container-button packet through {@link #clickMenuButton}, the same seam enchanting tables and looms
 * use; the server re-validates each press against the live state.</p>
 */
public final class FreePlayEnderChestMenu extends AbstractContainerMenu {

    /** Menu button ids. */
    public static final int BUTTON_EXPAND = 0;
    public static final int BUTTON_SHRINK = 1;

    /** Which button the screen should show, if any. */
    public enum Offer { NONE, EXPAND, SHRINK }

    private final Container container;
    private final boolean expanded;
    private final Offer offer;
    /** The title this menu was opened under, so a resize reopens with the same one. Server side only. */
    private final Component title;

    private FreePlayEnderChestMenu(int containerId, Inventory inventory, Container container,
                                   boolean expanded, Offer offer, Component title) {
        super(ModMenuTypes.FREE_PLAY_ENDER_CHEST.get(), containerId);
        int chestSlots = EnderChestLayout.slots(expanded);
        checkContainerSize(container, chestSlots);
        this.container = container;
        this.expanded = expanded;
        this.offer = offer;
        this.title = title;
        container.startOpen(inventory.player);

        for (int i = 0; i < chestSlots; i++) {
            addSlot(new Slot(container, i, EnderChestLayout.slotX(i, expanded), EnderChestLayout.slotY(i, expanded)));
        }
        // Player inventory where vanilla's chest puts it for this many rows (ChestMenu's `i` offset).
        int rowsOffset = (EnderChestLayout.rows(expanded) - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + rowsOffset));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 161 + rowsOffset));
        }
    }

    /** Client side: rebuilt from {@link #writeExtra}'s data with a stand-in container. */
    public static FreePlayEnderChestMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        boolean expanded = buf.readBoolean();
        Offer offer = buf.readEnum(Offer.class);
        return new FreePlayEnderChestMenu(containerId, inventory,
            new SimpleContainer(EnderChestLayout.slots(expanded)), expanded, offer, null);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public Offer offer() {
        return offer;
    }

    /** Number of chest slots in this menu (the player-inventory slots follow them). */
    public int chestSlots() {
        return EnderChestLayout.slots(expanded);
    }

    /** Client-side mirror of {@link EnderChestExpansion#extraSlotsEmpty}: true when no extra slot holds an item. */
    public boolean extraSlotsEmpty() {
        for (int i = EnderChestLayout.VANILLA_SLOTS; i < chestSlots(); i++) {
            if (slots.get(i).hasItem()) return false;
        }
        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer)) return false;
        boolean changed = switch (id) {
            case BUTTON_EXPAND -> offer == Offer.EXPAND && EnderChestExpansion.expand(serverPlayer);
            case BUTTON_SHRINK -> offer == Offer.SHRINK && EnderChestExpansion.shrink(serverPlayer);
            default -> false;
        };
        if (!changed) return false; // no longer eligible — leave the menu as it is
        // The live container was replaced; this menu still wraps the old one. Reopen over the new one
        // (openMenu closes this menu first).
        open(serverPlayer, title);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return result;
        ItemStack moving = slot.getItem();
        result = moving.copy();
        int chest = chestSlots();
        if (index < chest) {
            if (!moveItemStackTo(moving, chest, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(moving, 0, chest, false)) {
            return ItemStack.EMPTY;
        }
        if (moving.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return result;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        container.stopOpen(player);
    }

    /**
     * Open the Free Play chest for {@code player} at whatever size they currently see, under
     * {@code title}. Same shape as vanilla's ender-chest provider, so the {@code setActiveChest} the
     * block did beforehand still applies.
     */
    public static void open(ServerPlayer player, Component title) {
        player.openMenu(provider(player, title), buf -> writeExtra(buf, player));
    }

    /** A provider for {@code player}'s current view of their chest — for {@code openMenu}. */
    public static MenuProvider provider(ServerPlayer player, Component title) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
                return new FreePlayEnderChestMenu(containerId, inventory, player.getEnderChestInventory(),
                    EnderChestExpansion.showsExpanded(player), offerFor(player), title);
            }
        };
    }

    /** The extra open-screen data {@link #fromNetwork} reads: expanded, then which button applies. */
    public static void writeExtra(RegistryFriendlyByteBuf buf, ServerPlayer player) {
        buf.writeBoolean(EnderChestExpansion.showsExpanded(player));
        buf.writeEnum(offerFor(player));
    }

    private static Offer offerFor(ServerPlayer player) {
        if (EnderChestExpansion.canExpand(player)) return Offer.EXPAND;
        if (EnderChestExpansion.canOfferShrink(player)) return Offer.SHRINK;
        return Offer.NONE;
    }
}
