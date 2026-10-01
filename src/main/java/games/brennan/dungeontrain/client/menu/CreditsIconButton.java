package games.brennan.dungeontrain.client.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A square title-screen icon button carrying a vanilla <b>book</b> item as its
 * glyph — the entry point to the {@code CreditsScreen}. Drawn as the standard
 * vanilla button sprite (highlighted on hover/focus, so it reads as a real menu
 * control) with a 16px book {@link ItemStack} centred on top via
 * {@code GuiGraphics#renderItem}, so no bespoke texture asset has to ship.
 *
 * <p>An {@link ItemIconButton} with the book baked in; sits in the otherwise
 * empty top-right corner of the title screen (top-left is the version widget).</p>
 *
 * <p>For a UI concept rather than a thing in the world — a folder, a magnifier — use
 * {@link SpriteIconButton} instead, which carries a GUI sprite as its glyph.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CreditsIconButton extends ItemIconButton {

    public CreditsIconButton(int x, int y, int size, Component narration, OnPress onPress) {
        super(x, y, size, new ItemStack(Items.BOOK), narration, onPress);
    }
}
