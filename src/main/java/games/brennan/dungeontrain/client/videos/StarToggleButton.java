package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.function.BooleanSupplier;

/**
 * The "Dev faves" toggle on the Videos page toolbar: a dark square button carrying a ★ — gold and
 * filled while the filter is on, a grey outline ☆ while it is off. The tooltip says which.
 */
@OnlyIn(Dist.CLIENT)
public final class StarToggleButton extends DarkTintedButton {

    private static final int ON_COLOUR = 0xFFF5C542;
    private static final int OFF_COLOUR = 0xFF9A9A9A;

    private final BooleanSupplier on;

    public StarToggleButton(int x, int y, int size, BooleanSupplier on, OnPress onPress) {
        // The glyph is drawn here rather than as the message so it can be coloured by state.
        super(x, y, size, size, CommonComponents.EMPTY, onPress);
        this.on = on;
        refreshTooltip();
    }

    /** Re-read the state into the tooltip — called by the screen after every toggle. */
    public void refreshTooltip() {
        setTooltip(Tooltip.create(Component.translatable(on.getAsBoolean()
                ? "gui.dungeontrain.videos.filter.dev_faves.on"
                : "gui.dungeontrain.videos.filter.dev_faves.off")));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(g, mouseX, mouseY, partialTick);
        boolean lit = on.getAsBoolean();
        String glyph = lit ? "★" : "☆";
        var font = Minecraft.getInstance().font;
        g.drawString(font, glyph, getX() + (getWidth() - font.width(glyph)) / 2,
                getY() + (getHeight() - font.lineHeight) / 2 + 1, lit ? ON_COLOUR : OFF_COLOUR);
    }

    @Override
    public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.translatable("gui.dungeontrain.videos.filter.dev_faves.narration"));
    }
}
