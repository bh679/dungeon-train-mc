package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One image tile on the {@link TrainBuilderScreen} picker: a screenshot of what you'd be
 * building, with the mode name on a dark strip along the bottom and a white border on hover.
 *
 * <p>The tile images are not in the repo yet, so the texture's presence is probed once at
 * construction and a missing PNG falls back to a flat slate panel with the label centred —
 * the same guard {@code DeveloperWelcomePopupScreen} uses for its avatar. Without it a missing
 * file renders as the black-and-magenta checkerboard, which reads as a bug rather than as
 * artwork that hasn't landed yet.</p>
 *
 * <p>Presence is checked in the constructor rather than per-frame because screens rebuild all
 * their widgets in {@code init()}, which also runs on a resource reload.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileButton extends Button {

    /**
     * Nominal source rect. {@code GuiGraphics.blit} divides u/uWidth by the declared texture
     * size, so passing the same numbers for both samples the whole image whatever its real
     * pixel dimensions are — which matters here because the tile art is authored as
     * screenshots at whatever resolution the shot was taken.
     */
    private static final int SRC = 16;

    private static final int BORDER_HOVER = 0xFFFFFFFF;
    private static final int BORDER_IDLE = 0xFF000000;
    private static final int LABEL_STRIP_BG = 0xC0101010;
    private static final int FALLBACK_BG = 0xFF3A4048;
    private static final int LABEL_COLOUR = 0xFFFFFF;

    private static final int LABEL_STRIP_H = 16;

    private final BuilderMode mode;
    private final ResourceLocation texture;
    private final boolean textureAvailable;

    BuilderTileButton(int x, int y, int width, int height, BuilderMode mode, OnPress onPress) {
        super(x, y, width, height, Component.translatable(mode.labelKey()), onPress, DEFAULT_NARRATION);
        this.mode = mode;
        this.texture = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, mode.texturePath());
        this.textureAvailable = Minecraft.getInstance().getResourceManager()
                .getResource(this.texture).isPresent();
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

        if (textureAvailable) {
            RenderSystem.enableBlend();
            g.setColor(1.0F, 1.0F, 1.0F, this.alpha);
            g.blit(texture, x, y, w, h, 0.0F, 0.0F, SRC, SRC, SRC, SRC);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        } else {
            g.fill(x, y, x + w, y + h, FALLBACK_BG);
        }

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
