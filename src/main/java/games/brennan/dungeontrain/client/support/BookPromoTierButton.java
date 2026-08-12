package games.brennan.dungeontrain.client.support;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
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
 * A book-promotion price point drawn as <b>what you get</b>: a row of written-book item icons — one
 * per book the tier buys — above the price label, on a blue-tinted button.
 *
 * <p>Showing the books themselves rather than only a figure is the point of the widget. The player
 * is buying copies of their own writing onto more trains, so the quantity is the product, not a
 * multiplier hidden in a tooltip; seven drawn books say that faster than "$70" does.</p>
 *
 * <p>The icons <b>overlap into a fan</b> when a full row would not fit, which is what lets one, three
 * and seven books share a button width and still read as a stack that grows. Spacing is derived from
 * the width available at render time, so it survives a resize and any GUI scale rather than assuming
 * the layout {@link BookPromoScreen} happened to compute.</p>
 *
 * <p>Sprite + tint handling mirrors {@link games.brennan.dungeontrain.client.menu.ColorTintedButton}
 * (which is final, and centres its label over the whole widget — neither of which suits a two-band
 * button). Kept beside the promo screen rather than in {@code client.menu} because the written-book
 * icon makes it specific to this surface.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BookPromoTierButton extends Button {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted")
    );

    /** Same blue the donation surfaces give their Stripe-backed price points. */
    private static final float RED = 0.35F, GREEN = 0.62F, BLUE = 1.00F;
    private static final float HOVER_BOOST = 1.15F;

    /** Vanilla item-icon size. */
    private static final int ICON = 16;
    /** Padding either side of the icon row, and the gap between the row and the label. */
    private static final int PAD = 6;
    private static final int ICON_TOP = 6;
    private static final int LABEL_BOTTOM_GAP = 5;

    /** Height the icon row + label need; the screen sizes its buttons from this. */
    public static final int HEIGHT = ICON_TOP + ICON + 4 + 9 + LABEL_BOTTOM_GAP;

    private final int books;

    public BookPromoTierButton(int x, int y, int width, Component message, int books, OnPress onPress) {
        super(x, y, width, HEIGHT, message, onPress, DEFAULT_NARRATION);
        this.books = Math.max(1, books);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();

        float boost = this.isHoveredOrFocused() ? HOVER_BOOST : 1.0F;
        g.setColor(Math.min(1.0F, RED * boost), Math.min(1.0F, GREEN * boost), Math.min(1.0F, BLUE * boost), this.alpha);
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        g.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()),
                this.getX(), this.getY(), this.getWidth(), this.getHeight());
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);

        renderBooks(g);

        int textColor = this.active ? 0xFFFFFF : 0xA0A0A0;
        g.drawCenteredString(mc.font, this.getMessage(),
                this.getX() + this.getWidth() / 2,
                this.getY() + this.getHeight() - LABEL_BOTTOM_GAP - mc.font.lineHeight,
                textColor);
    }

    /** The stack of written books, centred, fanned tighter the more of them there are. */
    private void renderBooks(GuiGraphics g) {
        int available = this.getWidth() - 2 * PAD;
        // Step between icons: full width apart when they fit, otherwise as much overlap as needed.
        // Floored at 4px so a very narrow button degrades to a dense fan rather than one blob.
        int step = books > 1 ? Math.min(ICON, Math.max(4, (available - ICON) / (books - 1))) : 0;
        int rowW = ICON + step * (books - 1);
        int x = this.getX() + (this.getWidth() - rowW) / 2;
        int y = this.getY() + ICON_TOP;

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        for (int i = 0; i < books; i++) {
            g.renderItem(book, x + i * step, y);
        }
    }
}
