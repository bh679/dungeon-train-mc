package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.Util;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The translation preview's in-game scenes, drawn the way vanilla draws them — the action bar
 * ({@code Gui#renderOverlayMessage}), a hover tooltip ({@code GuiGraphics#renderTooltip} with the
 * default positioner), an item in a slot with its tooltip, and an advancement toast
 * ({@code AdvancementToast#render}).
 *
 * <p>Everything draws in the preview frame's own GUI pixels ({@code width}×{@code height}); the
 * caller has already scaled into it.</p>
 */
final class PreviewScenes {

    /** Vanilla's widget-tooltip wrap width ({@code Tooltip}'s 170px). */
    static final int WIDGET_TOOLTIP_WIDTH = 170;

    private static final ResourceLocation TOAST_SPRITE = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final int TOAST_W = 160;
    private static final int TOAST_H = 32;
    /** How long the toast shows its header before its title, when the title wraps. */
    private static final long TOAST_HEADER_MS = 1500;
    private static final long TOAST_CYCLE_MS = 4000;

    private PreviewScenes() {}

    // ---- action bar -----------------------------------------------------------------------

    /** The action bar message above the hotbar, with vanilla's backdrop. */
    static void actionBar(GuiGraphics g, Font font, Component message, int width, int height) {
        hotbar(g, width, height);
        int w = font.width(message);
        g.pose().pushPose();
        g.pose().translate(width / 2f, height - 68f, 0);
        g.drawStringWithBackdrop(font, message, -w / 2, -4, w, 0xFFFFFFFF);
        g.pose().popPose();
    }

    /** Where the hotbar sits, so a message has the context it is read in. */
    static void hotbar(GuiGraphics g, int width, int height) {
        int left = (width - 182) / 2;
        g.fill(left, height - 22, left + 182, height, 0x60000000);
    }

    // ---- tooltips -------------------------------------------------------------------------

    /**
     * A hover tooltip at the mouse ({@code mouseX}, {@code mouseY}), placed and kept on screen the way
     * vanilla's default positioner does, with the extra gap under the first line vanilla leaves.
     */
    static void tooltip(GuiGraphics g, Font font, List<FormattedCharSequence> lines, int mouseX, int mouseY,
                        int width, int height) {
        if (lines.isEmpty()) {
            return;
        }
        int w = lines.stream().mapToInt(font::width).max().orElse(0);
        int h = lines.size() == 1 ? 8 : 8 + 2 + (lines.size() - 1) * 10;
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + w > width) {
            x = Math.max(mouseX - 24 - w, 4);
        }
        if (y + h + 3 > height) {
            y = height - h - 3;
        }
        g.pose().pushPose();
        TooltipRenderUtil.renderTooltipBackground(g, x, y, w, h, 400);
        g.pose().translate(0, 0, 400);
        int ly = y;
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x, ly, 0xFFFFFFFF);
            ly += i == 0 ? 12 : 10;
        }
        g.pose().popPose();
    }

    /** An item in an inventory slot mid-frame, hovered, its tooltip showing. */
    static void itemTooltip(GuiGraphics g, Font font, ItemStack stack, List<Component> lines, int width, int height) {
        int sx = width / 3;
        int sy = height / 3;
        g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF8B8B8B);
        g.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF);
        g.renderItem(stack, sx, sy);
        tooltip(g, font, lines.stream().map(Component::getVisualOrderText).toList(), sx + 8, sy + 8, width, height);
    }

    // ---- advancement toast ----------------------------------------------------------------

    /**
     * The advancement toast in the top-right corner. A title that wraps shows the frame's header
     * first, then the title's lines, as vanilla's toast does — looped, so both can be read.
     */
    static void toast(GuiGraphics g, Font font, AdvancementType frame, ItemStack icon, Component title, int width) {
        g.pose().pushPose();
        g.pose().translate(width - TOAST_W, 0, 0);
        g.blitSprite(TOAST_SPRITE, 0, 0, TOAST_W, TOAST_H);
        List<FormattedCharSequence> lines = font.split(title, 125);
        int colour = frame == AdvancementType.CHALLENGE ? 0xFF88FF : 0xFFFF00;
        if (lines.size() == 1) {
            g.drawString(font, frame.getDisplayName(), 30, 7, colour | 0xFF000000, false);
            g.drawString(font, lines.get(0), 30, 18, -1, false);
        } else {
            long t = Util.getMillis() % TOAST_CYCLE_MS;
            if (t < TOAST_HEADER_MS) {
                int alpha = Mth.floor(Mth.clamp((TOAST_HEADER_MS - t) / 300f, 0f, 1f) * 255f) << 24 | 0x04000000;
                g.drawString(font, frame.getDisplayName(), 30, 11, colour | alpha, false);
            } else {
                int alpha = Mth.floor(Mth.clamp((t - TOAST_HEADER_MS) / 300f, 0f, 1f) * 252f) << 24 | 0x04000000;
                int ly = TOAST_H / 2 - lines.size() * 9 / 2;
                for (FormattedCharSequence line : lines) {
                    g.drawString(font, line, 30, ly, 0xFFFFFF | alpha, false);
                    ly += 9;
                }
            }
        }
        g.renderFakeItem(icon, 8, 8);
        g.pose().popPose();
    }

    /** How many lines the toast's title wraps to (vanilla wraps at 125px). */
    static int toastTitleLines(Font font, Component title) {
        return font.split(title, 125).size();
    }
}
