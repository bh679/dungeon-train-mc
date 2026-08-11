package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One image tile on the {@link TrainBuilderScreen} picker: a screenshot of what you'd be
 * building, with the mode name on a dark strip along the bottom and a white border on hover.
 *
 * <p>The image itself — and the slate fallback for art that isn't in the repo yet — is
 * {@link BuilderTileArt}, shared with the New screen's mode row so both fail the same way.
 * Presence is checked in the constructor rather than per-frame because screens rebuild all
 * their widgets in {@code init()}, which also runs on a resource reload.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileButton extends Button {

    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_IDLE = 0xFF000000;
    private static final int LABEL_STRIP_BG = 0xC0101010;
    private static final int LABEL_COLOUR = 0xFFFFFF;

    private static final int LABEL_STRIP_H = 16;

    private final BuilderMode mode;
    private final boolean textureAvailable;

    BuilderTileButton(int x, int y, int width, int height, BuilderMode mode, OnPress onPress) {
        super(x, y, width, height, Component.translatable(mode.labelKey()), onPress, DEFAULT_NARRATION);
        this.mode = mode;
        this.textureAvailable = BuilderTileArt.isAvailable(mode);
    }

    BuilderMode mode() {
        return mode;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hovered = this.isHoveredOrFocused();

        BuilderTileArt.render(g, mode, textureAvailable, x, y, w, h, this.alpha);

        // Dim the whole tile slightly until it's hovered, so the focused option pops.
        if (!hovered) {
            g.fill(x, y, x + w, y + h, 0x40000000);
        }

        g.renderOutline(x, y, w, h, hovered ? BORDER_HOVER : BORDER_IDLE);

        int stripTop = y + h - LABEL_STRIP_H;
        g.fill(x + 1, stripTop, x + w - 1, y + h - 1, LABEL_STRIP_BG);
        Minecraft mc = Minecraft.getInstance();
        g.drawCenteredString(mc.font, this.getMessage(), x + w / 2,
                stripTop + (LABEL_STRIP_H - mc.font.lineHeight) / 2, LABEL_COLOUR);
    }
}
