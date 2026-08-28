package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CustomContentGate;
import net.minecraft.client.gui.Font;
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
 * <p>One icon in two finishes rather than two icons: the Free Play badge, full colour when the
 * designs are in play and dimmed to grey when they aren't. A swap between two different marks
 * makes the reader identify the artwork before they can read the state; lit-versus-dim is the same
 * on/off read a light switch gives, and it keeps the button's identity fixed while its state
 * changes. State is re-read every frame, because Options → Custom Train Content can change it
 * behind this widget's back.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CustomContentToggleButton extends Button {

    /** The badge a Free Play run wears — the same sprite the popup's "Custom Train" card shows. */
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/free_play");

    private static final Component TOOLTIP_ON =
            Component.translatable("gui.dungeontrain.custom_content.toggle.on");
    private static final Component TOOLTIP_OFF =
            Component.translatable("gui.dungeontrain.custom_content.toggle.off");

    /** Sprite side length inside the button, leaving a border at the vanilla 20px button size. */
    private static final int ICON_SIZE = 16;

    /** Tint multiplier for the off state: dark enough to read as grey, dim enough to read as off. */
    private static final float OFF_TINT = 0.32F;
    private static final float OFF_ALPHA = 0.65F;

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

    /**
     * No label. The button's message is carried for narration only — vanilla would otherwise draw
     * it across the icon, and at a 20px square there is no room for a word anyway.
     */
    @Override
    public void renderString(GuiGraphics guiGraphics, Font font, int color) {
        // Intentionally blank.
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Vanilla's button frame (and its own hover/focus highlight) first, then the icon over it.
        super.renderWidget(g, mouseX, mouseY, partialTick);

        boolean on = CustomContentGate.contentEnabled();
        RenderSystem.enableBlend();
        if (!on) {
            g.setColor(OFF_TINT, OFF_TINT, OFF_TINT, OFF_ALPHA);
        }
        // blitSprite, not blit: this lives in the GUI sprite atlas (same reason as
        // ContentChoiceCard's icons).
        g.blitSprite(ICON,
                getX() + (getWidth() - ICON_SIZE) / 2,
                getY() + (getHeight() - ICON_SIZE) / 2,
                ICON_SIZE, ICON_SIZE);
        if (!on) {
            // GuiGraphics' colour is global state — leaving it set would tint every widget drawn
            // after this one on the same screen.
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
