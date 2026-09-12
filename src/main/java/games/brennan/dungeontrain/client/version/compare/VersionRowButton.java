package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One version row on the Versions page: a heading ("Modrinth latest is v0.849.0") over a detail
 * line ("CurseForge is 4 versions behind Modrinth — …"). Two lines because the detail is a whole
 * sentence in any language and a single centred button label would clip it to nothing.
 *
 * <p>The selected row is tinted blue so the changelog underneath reads as belonging to it; the
 * rest are the page's usual dark buttons. The tint is fixed at construction (as on every
 * {@link DarkTintedButton}), so the page rebuilds its rows when the selection moves.</p>
 */
@OnlyIn(Dist.CLIENT)
final class VersionRowButton extends DarkTintedButton {

    static final int HEIGHT = 30;

    private static final float SELECTED_R = 0.45F;
    private static final float SELECTED_G = 0.6F;
    private static final float SELECTED_B = 1.0F;
    private static final float PLAIN = 0.6F;

    private static final int PAD_X = 6;
    private static final int COLOUR_DETAIL = 0xC8C8C8;
    private static final int COLOUR_DETAIL_MUTED = 0x909090;

    private final Component detail;
    private final boolean muted;

    VersionRowButton(int x, int y, int width, Component heading, Component detail, boolean muted,
                     boolean selected, OnPress onPress) {
        super(x, y, width, HEIGHT, heading, onPress,
                selected ? SELECTED_R : PLAIN, selected ? SELECTED_G : PLAIN, selected ? SELECTED_B : PLAIN);
        this.detail = detail;
        this.muted = muted;
        // A long sentence is clipped in the row; hovering shows all of it.
        if (!detail.getString().isBlank()) {
            setTooltip(Tooltip.create(detail));
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draw the sprite with no label, then place the two lines ourselves.
        Component heading = getMessage();
        setMessage(Component.empty());
        super.renderWidget(g, mouseX, mouseY, partialTick);
        setMessage(heading);

        Font font = Minecraft.getInstance().font;
        int alpha = Mth.ceil(this.alpha * 255.0F) << 24;
        int textW = getWidth() - PAD_X * 2;
        int headingY = getY() + 5;
        int detailY = headingY + font.lineHeight + 3;
        int headingColour = (muted ? 0xA0A0A0 : 0xFFFFFF) | alpha;
        int detailColour = (muted ? COLOUR_DETAIL_MUTED : COLOUR_DETAIL) | alpha;

        g.enableScissor(getX() + PAD_X, getY(), getX() + PAD_X + textW, getY() + getHeight());
        g.drawString(font, clip(font, heading, textW), getX() + PAD_X, headingY, headingColour, true);
        g.drawString(font, clip(font, detail, textW), getX() + PAD_X, detailY, detailColour, false);
        g.disableScissor();
    }

    private static Component clip(Font font, Component text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        String plain = font.plainSubstrByWidth(text.getString(), width - font.width(ellipsis));
        return Component.literal(plain + ellipsis);
    }
}
