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
 * A square icon button carrying a vanilla <b>paper</b> item as its glyph — the entry point to the
 * {@code AiPolicyScreen} from the Credits page's bottom row. Paper rather than a book because the
 * book is already the Credits icon on the title screen ({@link CreditsIconButton}) and the two
 * would read as the same control; a policy is a document either way.
 *
 * <p>Same construction as {@link CreditsIconButton}: the standard vanilla button sprite
 * (highlighted on hover/focus, so it reads as a real menu control) with a 16px {@link ItemStack}
 * centred on top via {@link GuiGraphics#renderItem}, so no bespoke texture asset has to ship.</p>
 *
 * <p>The button carries no label, so the caller must set a {@code Tooltip} — that hover text is
 * the only thing naming what it opens.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AiPolicyIconButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    /** Vanilla item-icon side length in pixels. */
    private static final int ICON = 16;

    private final ItemStack icon = new ItemStack(Items.PAPER);

    public AiPolicyIconButton(int x, int y, int size, Component narration, OnPress onPress) {
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
