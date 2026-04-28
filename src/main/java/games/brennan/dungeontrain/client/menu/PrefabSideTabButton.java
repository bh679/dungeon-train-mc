package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.mixin.client.CreativeModeInventoryScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Vertical tab button on the LEFT edge of the creative inventory.
 *
 * <p>Acts as a shortcut into one of our registered {@link CreativeModeTab}s
 * — {@code onPress} calls vanilla's private {@code selectTab} via
 * {@link CreativeModeInventoryScreenAccessor}, which then drives the
 * standard items-grid / title / scrollbar / tooltip rendering. No custom
 * overpainting required.</p>
 *
 * <p>The button is visually highlighted when its target tab is the
 * currently-selected vanilla tab, so the user can tell at a glance which
 * mode they're in.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabSideTabButton extends AbstractButton {

    public static final int WIDTH = 28;
    public static final int HEIGHT = 32;

    private static final int FILL_INACTIVE = 0xFF2B2B2B;
    private static final int FILL_HOVER = 0xFF4A4A4A;
    private static final int FILL_ACTIVE = 0xFF6E6E6E;
    private static final int BORDER = 0xFF000000;
    private static final int BORDER_LIGHT = 0xFFA0A0A0;

    private final CreativeModeTab targetTab;
    private final ItemStack iconStack;

    public PrefabSideTabButton(int x, int y, CreativeModeTab targetTab, ItemStack iconStack, Component label) {
        super(x, y, WIDTH, HEIGHT, label);
        this.targetTab = targetTab;
        this.iconStack = iconStack;
    }

    @Override
    public void onPress() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof CreativeModeInventoryScreen screen)) return;
        ((CreativeModeInventoryScreenAccessor) screen).dungeontrain$invokeSelectTab(targetTab);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean active = CreativeModeInventoryScreenAccessor.dungeontrain$getSelectedTab() == targetTab;
        int fill = active ? FILL_ACTIVE : (isHovered() ? FILL_HOVER : FILL_INACTIVE);

        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + WIDTH;
        int y1 = y0 + HEIGHT;

        g.fill(x0, y0, x1, y1, fill);

        // Light border on top + left, dark on bottom + right — extruded look.
        g.fill(x0, y0, x1, y0 + 1, BORDER_LIGHT);
        g.fill(x0, y0, x0 + 1, y1, BORDER_LIGHT);
        g.fill(x0, y1 - 1, x1, y1, BORDER);
        g.fill(x1 - 1, y0, x1, y1, BORDER);

        int iconX = x0 + (WIDTH - 16) / 2;
        int iconY = y0 + (HEIGHT - 16) / 2;
        g.renderItem(iconStack, iconX, iconY);

        if (isHovered()) {
            g.renderTooltip(Minecraft.getInstance().font, getMessage(), mouseX, mouseY);
        }
    }
}
