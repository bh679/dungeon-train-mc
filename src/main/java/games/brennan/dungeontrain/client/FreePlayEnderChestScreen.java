package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.player.EnderChestExpansion;
import games.brennan.dungeontrain.player.FreePlayEnderChestMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Free Play Ender Chest screen: vanilla's chest look at three or nine rows, plus the
 * <b>Expand ×3</b> button while the chest is still the small one.
 *
 * <p>Vanilla's {@code ContainerScreen} blits the chest body as one strip off {@code generic_54.png},
 * which only holds six rows; past that it would be painting the player-inventory half of the texture.
 * So the body is drawn in three parts — header, one 18px row strip tiled per row, then the
 * player-inventory block — and nine rows come out of the same texture.</p>
 *
 * <p>The button sits on the right of the "Inventory" label row, clear of the (translated, possibly
 * long) title. It sends vanilla's container-button packet; the server does the rest and reopens the
 * screen at nine rows — see {@link FreePlayEnderChestMenu#clickMenuButton}.</p>
 */
public final class FreePlayEnderChestScreen extends AbstractContainerScreen<FreePlayEnderChestMenu>
        implements MenuAccess<FreePlayEnderChestMenu> {

    public static final String KEY_EXPAND = "gui.dungeontrain.enderchest.expand";
    public static final String KEY_EXPAND_TOOLTIP = "gui.dungeontrain.enderchest.expand.tooltip";

    private static final ResourceLocation TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 18;
    /** Where the player-inventory block starts in the texture: after the header and six rows. */
    private static final int TEXTURE_FOOTER_V = HEADER_HEIGHT + 6 * ROW_HEIGHT + 1;
    private static final int FOOTER_HEIGHT = 96;

    private static final int BUTTON_WIDTH = 64;
    private static final int BUTTON_HEIGHT = 12;

    private final int rows;

    public FreePlayEnderChestScreen(FreePlayEnderChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.rows = menu.getRowCount();
        this.imageHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + FOOTER_HEIGHT + 1;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        if (!menu.isExpandable()) {
            return;
        }
        int x = leftPos + imageWidth - 8 - BUTTON_WIDTH;
        int y = topPos + inventoryLabelY - 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY_EXPAND), b -> expand())
            .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
            .tooltip(Tooltip.create(Component.translatable(KEY_EXPAND_TOOLTIP, EnderChestExpansion.EXPANDED_SLOTS)))
            .build());
    }

    private void expand() {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, FreePlayEnderChestMenu.BUTTON_EXPAND);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, HEADER_HEIGHT);
        for (int row = 0; row < rows; row++) {
            graphics.blit(TEXTURE, x, y + HEADER_HEIGHT + row * ROW_HEIGHT, 0, HEADER_HEIGHT, imageWidth, ROW_HEIGHT);
        }
        graphics.blit(TEXTURE, x, y + HEADER_HEIGHT + rows * ROW_HEIGHT, 0, TEXTURE_FOOTER_V, imageWidth,
            FOOTER_HEIGHT);
    }
}
