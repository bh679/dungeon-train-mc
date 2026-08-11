package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The doorway into the translation editor, built once and hung on more than one screen.
 *
 * <p>There are two: above the title screen's Language button, and beside Done on the Language
 * screen itself. They are one feature in two places, not two features, so the sprite, the size and
 * the tooltip live here — a second copy of that construction is exactly how the two would drift
 * into looking like different things. Each screen keeps its own positioning, because anchoring is
 * the part that genuinely differs.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TranslateButtons {

    /**
     * Realms' pencil-and-paper icon. Reusing a vanilla sprite rather than shipping our own keeps
     * this consistent with the menu-chat button, which borrows {@code icon/invite} the same way.
     */
    private static final ResourceLocation EDIT_SPRITE =
        ResourceLocation.withDefaultNamespace("icon/draft_report");
    private static final int SPRITE_W = 15;
    private static final int SPRITE_H = 15;
    public static final int BUTTON_SIZE = 20;

    private TranslateButtons() {}

    /**
     * A button that opens the editor on {@code target}, returning to {@code parent} when closed.
     * Unpositioned — the caller anchors it to whatever it sits beside.
     */
    public static SpriteIconButton create(Screen parent, String target) {
        SpriteIconButton button = SpriteIconButton.builder(
                Component.translatable("gui.dungeontrain.translate.button"),
                b -> Minecraft.getInstance().setScreen(new TranslationScreen(parent, target)),
                true)
            .width(BUTTON_SIZE)
            .sprite(EDIT_SPRITE, SPRITE_W, SPRITE_H)
            .build();
        button.setTooltip(Tooltip.create(
            Component.translatable("gui.dungeontrain.translate.button.tooltip", target)));
        return button;
    }
}
