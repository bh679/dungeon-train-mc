package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square title-screen icon button carrying a vanilla <b>book</b> item as its
 * glyph — the entry point to the {@code CreditsScreen}. Drawn as the standard
 * vanilla button sprite (highlighted on hover/focus, so it reads as a real menu
 * control) with a 16px book {@link ItemStack} centred on top via
 * {@link GuiGraphics#renderItem}, so no bespoke texture asset has to ship.
 *
 * <p>Mirrors {@link DarkTintedButton}'s sprite approach; sits in the otherwise
 * empty top-right corner of the title screen (top-left is the version widget).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CreditsIconButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    /** Vanilla item-icon side length in pixels. */
    private static final int ICON = 16;

    private final ItemStack icon = new ItemStack(Items.BOOK);

    public CreditsIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, size, narration, onPress, DEFAULT_NARRATION);
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
