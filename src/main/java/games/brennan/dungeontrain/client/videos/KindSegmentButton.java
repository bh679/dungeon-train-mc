package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * One half of the submit screen's <b>Video | Livestream</b> switch. Both halves are always visible;
 * the selected one is drawn as a normal button with a light frame, the other one dimmed — so which is
 * active is obvious at a glance rather than hidden in a cycle-button label.
 */
@OnlyIn(Dist.CLIENT)
public final class KindSegmentButton extends DarkTintedButton {

    private static final int FRAME = 0xFFE6F2EA;
    private static final int DIM = 0x88000000;

    private final BooleanSupplier selected;

    public KindSegmentButton(int x, int y, int width, int height, Component message,
                             BooleanSupplier selected, OnPress onPress) {
        super(x, y, width, height, message, onPress);
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        int x = getX();
        int y = getY();
        int r = x + getWidth();
        int b = y + getHeight();
        if (selected.getAsBoolean()) {
            g.fill(x, y, r, y + 1, FRAME);
            g.fill(x, b - 1, r, b, FRAME);
            g.fill(x, y, x + 1, b, FRAME);
            g.fill(r - 1, y, r, b, FRAME);
        } else {
            g.fill(x, y, r, b, DIM);
        }
    }
}
