package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square button carrying a GUI <b>sprite</b> as its glyph — the sibling of
 * {@link CreditsIconButton}, which carries an item instead.
 *
 * <p>Both exist because the two glyph sources want different draw calls, and a single class that
 * rendered "an item, or a sprite, depending which field is null" would be worse than two small ones.
 * Reach for this one whenever the icon is a UI concept rather than a thing in the world: a folder is
 * not a chest, and export/import are not two kinds of book.</p>
 *
 * <p>Sprites may be vanilla's ({@code minecraft:icon/search}) or the mod's own, shipped under
 * {@code assets/dungeontrain/textures/gui/sprites/} like the book-vote thumbs.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SpriteIconButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    /**
     * Clear space between the glyph and the button's own edge. The button sprite's frame is about a
     * pixel, so anything less than this puts the icon ON the frame rather than inside it.
     */
    private static final int MIN_PADDING = 2;

    private final ResourceLocation icon;
    private final int iconSize;

    /**
     * @param iconSize the sprite's AUTHORED size. Passed rather than derived because sprites differ
     *                 — vanilla's {@code icon/search} is 12px, the mod's own icons are 16 — and
     *                 drawing each at its own size is the whole reason they stay crisp. Clamped to
     *                 leave {@link #MIN_PADDING} on every side, so no caller can crowd the frame.
     */
    public SpriteIconButton(int x, int y, int size, ResourceLocation icon, int iconSize,
                            Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
        this.icon = icon;
        this.iconSize = Math.max(1, Math.min(iconSize, size - MIN_PADDING * 2));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());

        int ix = this.getX() + (this.getWidth() - iconSize) / 2;
        int iy = this.getY() + (this.getHeight() - iconSize) / 2;
        g.blitSprite(icon, ix, iy, iconSize, iconSize);
    }
}
