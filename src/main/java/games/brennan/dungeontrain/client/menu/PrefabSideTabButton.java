package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Vertical-tab button rendered on the LEFT edge of the creative inventory.
 * Toggles a {@link PrefabTabState.Tab} when clicked. Two instances: one
 * for variants (top), one for loot (bottom).
 *
 * <p>No vanilla {@code Button} subclass renders vertically — vanilla tab
 * buttons are part of the inventory background sprite, not widgets — so we
 * paint our own square panel. Active state uses a brighter fill so the user
 * sees which tab is currently expanded.</p>
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

    private final PrefabTabState.Tab tab;
    private final ItemStack iconStack;

    public PrefabSideTabButton(int x, int y, PrefabTabState.Tab tab, ItemStack iconStack, Component label) {
        super(x, y, WIDTH, HEIGHT, label);
        this.tab = tab;
        this.iconStack = iconStack;
    }

    @Override
    public void onPress() {
        PrefabTabState.toggle(tab);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean active = PrefabTabState.activeTab() == tab;
        int fill = active ? FILL_ACTIVE : (isHovered() ? FILL_HOVER : FILL_INACTIVE);

        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + WIDTH;
        int y1 = y0 + HEIGHT;

        // Body fill.
        g.fill(x0, y0, x1, y1, fill);

        // Outer border (1 px). Light on top + left for a tab-extruding-leftward
        // illusion; dark on bottom + right.
        g.fill(x0, y0, x1, y0 + 1, BORDER_LIGHT);
        g.fill(x0, y0, x0 + 1, y1, BORDER_LIGHT);
        g.fill(x0, y1 - 1, x1, y1, BORDER);
        g.fill(x1 - 1, y0, x1, y1, BORDER);

        // Item icon centered.
        int iconX = x0 + (WIDTH - 16) / 2;
        int iconY = y0 + (HEIGHT - 16) / 2;
        g.renderItem(iconStack, iconX, iconY);
    }
}
