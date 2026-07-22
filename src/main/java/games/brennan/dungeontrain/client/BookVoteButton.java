package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BooleanSupplier;

/**
 * One 👍/👎 button on the virtual vote page — vanilla {@code PageButton} pattern: a fixed-size
 * sprite widget whose highlighted variant lights on hover/focus, plus (unlike PageButton) whenever
 * this side is the player's CURRENT vote, so a cast vote stays visibly lit on reopen. Sprites are
 * mod-namespaced ({@code dungeontrain:widget/thumbs_*}) and auto-stitched into the gui atlas from
 * {@code textures/gui/sprites/widget/}, drawn in the same cream/orange palette as the vanilla page
 * arrows. Click sound is the default button click (page-turn is reserved for actual page turns).
 */
public final class BookVoteButton extends Button {

    public static final int SIZE = 18;

    private static final ResourceLocation UP_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_up");
    private static final ResourceLocation UP_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_up_highlighted");
    private static final ResourceLocation DOWN_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_down");
    private static final ResourceLocation DOWN_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/thumbs_down_highlighted");

    private final boolean isUp;
    private final BooleanSupplier selected;

    public BookVoteButton(int x, int y, boolean isUp, BooleanSupplier selected,
                          OnPress onPress, Component narration) {
        super(x, y, SIZE, SIZE, narration, onPress, DEFAULT_NARRATION);
        this.isUp = isUp;
        this.selected = selected;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        boolean lit = this.isHoveredOrFocused() || this.selected.getAsBoolean();
        ResourceLocation sprite = this.isUp
            ? (lit ? UP_HIGHLIGHTED_SPRITE : UP_SPRITE)
            : (lit ? DOWN_HIGHLIGHTED_SPRITE : DOWN_SPRITE);
        guiGraphics.blitSprite(sprite, this.getX(), this.getY(), SIZE, SIZE);
    }
}
