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
import net.minecraft.world.inventory.ChestMenu;

/**
 * The Ender Chest menu a Free Play run opens: a plain {@link ChestMenu} over the player's live chest at
 * three or nine rows, plus one menu button that grows it — see {@link EnderChestExpansion}.
 *
 * <p>Why not vanilla's menu: there is no nine-row {@code MenuType}, and the client screen needs to know
 * the row count and whether the button applies before it draws. Both travel in the open-screen packet's
 * extra data ({@link #writeExtra}/{@link #fromNetwork}) — no packet of DT's own. The button itself rides
 * vanilla's container-button packet through {@link #clickMenuButton}, the same seam enchanting tables
 * and looms use.</p>
 *
 * <p>Everything a slot does — shift-click, drag, the {@code checkContainerSize} guard — is
 * {@link ChestMenu}'s, untouched.</p>
 */
public final class FreePlayEnderChestMenu extends ChestMenu {

    /** The one menu button: expand. */
    public static final int BUTTON_EXPAND = 0;

    private final boolean expandable;
    /** The title this menu was opened under, so the expanded reopen keeps it. Server side only. */
    private final Component title;

    private FreePlayEnderChestMenu(int containerId, Inventory inventory, Container container, int rows,
                                   boolean expandable, Component title) {
        super(ModMenuTypes.FREE_PLAY_ENDER_CHEST.get(), containerId, inventory, container, rows);
        this.expandable = expandable;
        this.title = title;
    }

    /** Client side: rebuilt from {@link #writeExtra}'s data with a stand-in container. */
    public static FreePlayEnderChestMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        int rows = buf.readVarInt();
        boolean expandable = buf.readBoolean();
        return new FreePlayEnderChestMenu(containerId, inventory,
            new SimpleContainer(rows * EnderChestExpansion.COLUMNS), rows, expandable, null);
    }

    /** Whether the screen should offer the expand button. */
    public boolean isExpandable() {
        return expandable;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != BUTTON_EXPAND || !expandable || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (!EnderChestExpansion.expand(serverPlayer)) {
            return false; // no longer eligible — leave the menu as it is
        }
        // The live container was replaced; this menu still wraps the old one. Reopen over the new,
        // bigger one (openMenu closes this menu first).
        open(serverPlayer, title);
        return true;
    }

    /**
     * Open the Free Play chest for {@code player} at whatever size they currently see, under
     * {@code title}. The provider is shaped like vanilla's ender-chest one so the same
     * {@code setActiveChest} the block did beforehand still applies.
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
                    EnderChestExpansion.visibleRows(player), EnderChestExpansion.canExpand(player), title);
            }
        };
    }

    /** The extra open-screen data {@link #fromNetwork} reads: rows, then whether the button applies. */
    public static void writeExtra(RegistryFriendlyByteBuf buf, ServerPlayer player) {
        buf.writeVarInt(EnderChestExpansion.visibleRows(player));
        buf.writeBoolean(EnderChestExpansion.canExpand(player));
    }
}
