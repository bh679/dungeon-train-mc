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

    /** Glyph side length in pixels — the size the mod's own icon sprites are authored at. */
    private static final int ICON = 18;

    private final ResourceLocation icon;

    public SpriteIconButton(int x, int y, int size, ResourceLocation icon, Component narration,
                            OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
        this.icon = icon;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());

        int size = Math.min(ICON, Math.min(this.getWidth(), this.getHeight()));
        int ix = this.getX() + (this.getWidth() - size) / 2;
        int iy = this.getY() + (this.getHeight() - size) / 2;
        g.blitSprite(icon, ix, iy, size, size);
    }
}
