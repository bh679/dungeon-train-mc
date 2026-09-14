package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.player.EnderChestExpansion;
import games.brennan.dungeontrain.player.EnderChestLayout;
import games.brennan.dungeontrain.player.FreePlayEnderChestMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Free Play Ender Chest screen: vanilla's chest window at three or six rows, the two wings beside
 * it when expanded, and one button — <b>Expand</b> while the chest is plain, <b>Shrink</b> (enabled
 * only while every extra slot is empty) once it isn't.
 *
 * <p>The window is {@code generic_54.png} drawn in three parts — header, an 18px row strip per row,
 * the player-inventory block — which at six rows is exactly the double-chest look. The wings are drawn
 * the way Edible Backpacks draws its panels: outline, bevel, face, recessed slot insets, in flat GUI
 * colours sampled from vanilla's inventory texture, so they read as part of the same window family.
 * Slot items and highlights are the vanilla screen loop's; only the chrome is here.</p>
 *
 * <p>Because the wings sit outside the 176px window, {@link #hasClickedOutside} has to know about
 * them — otherwise a click on a wing slot would count as "outside the GUI" and drop the carried
 * stack.</p>
 */
public final class FreePlayEnderChestScreen extends AbstractContainerScreen<FreePlayEnderChestMenu> {

    public static final String KEY_EXPAND = "gui.dungeontrain.enderchest.expand";
    public static final String KEY_EXPAND_TOOLTIP = "gui.dungeontrain.enderchest.expand.tooltip";
    public static final String KEY_SHRINK = "gui.dungeontrain.enderchest.shrink";
    public static final String KEY_SHRINK_TOOLTIP = "gui.dungeontrain.enderchest.shrink.tooltip";

    private static final ResourceLocation TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");

    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 18;
    /** Where the player-inventory block starts in the texture: after the header and six rows. */
    private static final int TEXTURE_FOOTER_V = HEADER_HEIGHT + 6 * ROW_HEIGHT + 1;
    private static final int FOOTER_HEIGHT = 96;

    // Vanilla-inventory palette, sampled from gui/container/inventory.png (as Edible Backpacks does).
    private static final int FACE = 0xFFC6C6C6;
    private static final int OUTLINE = 0xFF000000;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADE = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B;
    /** Staircase depth of the outer corner cut, matching the vanilla texture's 3px corner triangle. */
    private static final int CORNER_CUT = 2;

    private static final int BUTTON_WIDTH = 56;
    private static final int BUTTON_HEIGHT = 12;

    private final int rows;
    private Button shrinkButton;

    public FreePlayEnderChestScreen(FreePlayEnderChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.rows = EnderChestLayout.rows(menu.isExpanded());
        this.imageHeight = HEADER_HEIGHT + rows * ROW_HEIGHT + FOOTER_HEIGHT + 1;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        shrinkButton = null;
        int x = leftPos + imageWidth - 8 - BUTTON_WIDTH;
        int y = topPos + inventoryLabelY - 2;
        switch (menu.offer()) {
            case EXPAND -> addRenderableWidget(Button.builder(Component.translatable(KEY_EXPAND),
                    b -> press(FreePlayEnderChestMenu.BUTTON_EXPAND))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable(KEY_EXPAND_TOOLTIP,
                    EnderChestExpansion.EXPANDED_SLOTS, EnderChestLayout.EXTRA_ROWS, EnderChestLayout.WING_COLUMNS)))
                .build());
            case SHRINK -> {
                shrinkButton = Button.builder(Component.translatable(KEY_SHRINK),
                        b -> press(FreePlayEnderChestMenu.BUTTON_SHRINK))
                    .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable(KEY_SHRINK_TOOLTIP,
                        EnderChestExpansion.VANILLA_SLOTS)))
                    .build();
                shrinkButton.active = menu.extraSlotsEmpty();
                addRenderableWidget(shrinkButton);
            }
            case NONE -> { }
        }
    }

    private void press(int buttonId) {
        if (minecraft == null || minecraft.gameMode == null) return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Enabled state follows the live contents, so moving the last stack out re-arms the button.
        if (shrinkButton != null) {
            shrinkButton.active = menu.extraSlotsEmpty();
        }
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
        if (menu.isExpanded()) {
            drawWing(graphics, false);
            drawWing(graphics, true);
        }
    }

    /** A click on a wing is a click on the GUI, not outside it. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        if (menu.isExpanded() && (insideWing(mouseX, mouseY, false) || insideWing(mouseX, mouseY, true))) {
            return false;
        }
        return super.hasClickedOutside(mouseX, mouseY, guiLeft, guiTop, button);
    }

    private boolean insideWing(double mouseX, double mouseY, boolean right) {
        int[] b = EnderChestLayout.wingBounds(right);
        return mouseX >= leftPos + b[0] && mouseX < leftPos + b[2]
            && mouseY >= topPos + b[1] && mouseY < topPos + b[3];
    }

    // ---- Wing chrome, after Edible Backpacks' BackpackScreenPanels ----

    private void drawWing(GuiGraphics g, boolean right) {
        int[] b = EnderChestLayout.wingBounds(right);
        int x0 = leftPos + b[0];
        int y0 = topPos + b[1];
        int x1 = leftPos + b[2];
        int y1 = topPos + b[3];
        fillRounded(g, x0, y0, x1, y1, OUTLINE, CORNER_CUT);
        drawBevel(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, CORNER_CUT - 1);
        g.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, FACE);

        // Slot insets — recessed, so their bevel is the panel's inverted: shadow top-left, light bottom-right.
        int start = right ? EnderChestLayout.RIGHT_WING_START : EnderChestLayout.LEFT_WING_START;
        for (int i = 0; i < EnderChestLayout.WING_SLOTS; i++) {
            int sx = leftPos + EnderChestLayout.slotX(start + i, true);
            int sy = topPos + EnderChestLayout.slotY(start + i, true);
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, SHADE);
            g.fill(sx, sy, sx + 18, sy + 18, LIGHT);
            g.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);
        }
    }

    /** A rect whose four corners are stepped back like vanilla's rounded GUI corners. */
    private static void fillRounded(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int cut) {
        for (int d = 0; d < cut; d++) {
            int inset = cut - d;
            g.fill(x0 + inset, y0 + d, x1 - inset, y0 + d + 1, color);
            g.fill(x0 + inset, y1 - d - 1, x1 - inset, y1 - d, color);
        }
        int m = Math.max(cut, 0);
        g.fill(x0, y0 + m, x1, y1 - m, color);
    }

    /** Bevel layer: light everywhere, shade along the bottom and right, lit from the top-left. */
    private static void drawBevel(GuiGraphics g, int x0, int y0, int x1, int y1, int cut) {
        fillRounded(g, x0, y0, x1, y1, LIGHT, cut);
        if (y1 - 1 > y0 + 1) g.fill(x1 - 1, y0 + 1, x1, y1 - 1, SHADE);
        int from = x0 + 1 + cut;
        int to = x1 - cut;
        if (to > from) g.fill(from, y1 - 1, to, y1, SHADE);
    }
}
