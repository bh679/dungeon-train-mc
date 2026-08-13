package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square menu icon button carrying an arbitrary vanilla <b>item</b> as its glyph — the
 * general form of {@link CreditsIconButton} (which is the same widget hardwired to a book).
 * Drawn as the standard vanilla button sprite (highlighted on hover/focus, so it reads as a
 * real menu control) with a 16px {@link ItemStack} centred on top, so no bespoke texture asset
 * has to ship.
 *
 * <p>Pick an item that <i>is</i> the thing being opened — a spyglass for the filming page, a
 * book for credits. For a UI concept rather than a thing in the world — a folder, a magnifier —
 * use {@code SpriteIconButton} instead, which carries a GUI sprite as its glyph.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ItemGlyphIconButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    /** Vanilla item-icon side length in pixels. */
    private static final int ICON = 16;

    private final ItemStack icon;

    public ItemGlyphIconButton(int x, int y, int size, Item glyph, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
        this.icon = new ItemStack(glyph);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());

        int ix = this.getX() + (this.getWidth() - ICON) / 2;
        int iy = this.getY() + (this.getHeight() - ICON) / 2;
        g.renderItem(icon, ix, iy);
    }
}
