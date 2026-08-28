package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CustomContentGate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Title-screen toggle for the player's own train designs, sitting immediately left of New Game and
 * shown only when this install actually has Train Editor content
 * ({@code EditorContentIntegrity.hasCustomContent()}).
 *
 * <p>It writes the same client preference the confirmation popup's "Remember decision" checkbox
 * writes, so pressing it also answers the per-world question from here on — {@code
 * CustomContentGate.askCounting} skips the popup for any preference that isn't ASK. That is the
 * point of the button: a player who knows which way they want it shouldn't be asked every world.</p>
 *
 * <p>Wears the two sprites the popup's own cards wear — {@code icon/free_play} when the designs
 * are in play (the badge that run will carry) and {@code icon/default_train} when they aren't — so
 * the button and the popup visibly answer the same question. State is read on every frame rather
 * than cached, because Options → Custom Train Content can change it behind this widget's back.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CustomContentToggleButton extends Button {

    /** Same sprites as {@code ContentChoiceCard}'s two answers. */
    private static final ResourceLocation ICON_ON =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/free_play");
    private static final ResourceLocation ICON_OFF =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/default_train");

    private static final Component TOOLTIP_ON =
            Component.translatable("gui.dungeontrain.custom_content.toggle.on");
    private static final Component TOOLTIP_OFF =
            Component.translatable("gui.dungeontrain.custom_content.toggle.off");

    /** Sprite side length inside the button, leaving a border at the vanilla 20px button size. */
    private static final int ICON_SIZE = 16;

    /** Translucent white wash on hover/focus, matching {@link BilibiliIconButton}. */
    private static final int HOVER_WASH = 0x33FFFFFF;

    public CustomContentToggleButton(int x, int y, int size, OnPress onPress) {
        super(x, y, size, size,
                Component.translatable("gui.dungeontrain.custom_content.toggle.narration"),
                onPress, DEFAULT_NARRATION);
        refreshTooltip();
    }

    /** Point the tooltip at whichever state the preference is in now. Call after every press. */
    public void refreshTooltip() {
        setTooltip(Tooltip.create(CustomContentGate.contentEnabled() ? TOOLTIP_ON : TOOLTIP_OFF));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Vanilla's button frame first, then the icon on top of it — the frame is what says this is
        // a control, and the two sprites alone read as decoration without it.
        super.renderWidget(g, mouseX, mouseY, partialTick);

        RenderSystem.enableBlend();
        // blitSprite, not blit: these live in the GUI sprite atlas (same reason as
        // ContentChoiceCard's icons).
        g.blitSprite(CustomContentGate.contentEnabled() ? ICON_ON : ICON_OFF,
                getX() + (getWidth() - ICON_SIZE) / 2,
                getY() + (getHeight() - ICON_SIZE) / 2,
                ICON_SIZE, ICON_SIZE);

        if (isHoveredOrFocused()) {
            g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1,
                    HOVER_WASH);
        }
    }
}
