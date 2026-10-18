package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.CopyLinkIconButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;

/**
 * The confirm shown before a <b>payment</b> link opens the browser: "Open Stripe checkout for
 * $30 AUD?", one line on where the browser goes and why the player's name is attached, Open and
 * Cancel. No URL.
 *
 * <p>Replaces vanilla's {@link ConfirmLinkScreen} for payment targets only. That screen prints the
 * whole checkout URL ({@code …/pay/cn?displayname=Steve&locale=en&qty=3}) above Open / Copy /
 * Cancel, and the funnel showed most players who had just pressed a money button cancelling on it:
 * a query string with their name in it looks like something to back away from. Discord, the
 * affiliate link and everything else keep the vanilla screen, because a plain URL is what those
 * players expect to see.</p>
 *
 * <p>Analytics are the same as before: the caller records the {@code click}; this screen records
 * {@link UiAnalytics#confirm} as {@code yes} on Open and {@code no} on Cancel or ESC, once, and
 * returns to the calling screen either way so a player can follow more than one link.</p>
 *
 * <p>Drawn as a narrow tooltip-framed panel like {@code FreePlayConfirmScreen}, measured in
 * {@link #init()}; the death screen's dev window is only 240 px tall, so the panel is title +
 * wrapped body + one button row and nothing else.</p>
 */
public final class PaymentConfirmScreen extends Screen {

    // Vanilla tooltip frame, as FreePlayConfirmScreen draws it.
    private static final int FRAME_BG = 0xF0100010;
    private static final int FRAME_BORDER_TOP = 0x505000FF;
    private static final int FRAME_BORDER_BOTTOM = 0x5028007F;
    // The donation screens' palette (DonationOptionsScreen.TITLE / NARR).
    private static final int COLOUR_TITLE = 0xFFE0B56A;
    private static final int COLOUR_BODY = 0xFFC7BDA7;
    private static final int COLOUR_SEPARATOR = 0x40FFFFFF;

    private static final int MAX_PANEL_W = 260;
    private static final int PADDING = 12;
    private static final int LINE_GAP = 1;
    private static final int TITLE_SEP_GAP = 8;
    private static final int BODY_BUTTON_GAP = 10;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 6;
    private static final int COPY_ICON = 7;

    private final Screen parent;
    private final String surface;
    private final String target;
    private final String url;
    private final Component body;
    private boolean responded = false;

    private int panelX, panelY, panelW, panelH;
    private int titleRelY, bodyRelY;
    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> bodyLines = List.of();

    PaymentConfirmScreen(Screen parent, String surface, String target, String url,
                         Component title, Component body) {
        super(title);
        this.parent = parent;
        this.surface = surface;
        this.target = target;
        this.url = url;
        this.body = body;
    }

    /**
     * Open this screen for {@code url} when {@code target} is a payment link, returning true; false
     * (and nothing opened) when it is not, so the caller falls through to vanilla's
     * {@link ConfirmLinkScreen}. The caller has already recorded the {@code click}.
     */
    public static boolean tryOpen(Screen parent, String surface, String target, String url) {
        PaymentConfirmCopy copy = PaymentConfirmCopy.forTarget(target, url);
        if (copy == null || url == null) return false;
        Component title = copy.amountKey() == null
                ? Component.translatable(copy.titleKey())
                : Component.translatable(copy.titleKey(), Component.translatable(copy.amountKey()));
        Minecraft.getInstance().setScreen(new PaymentConfirmScreen(
                parent, surface, target, url, title, Component.translatable(copy.bodyKey())));
        return true;
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_PANEL_W, this.width - 40);
        int innerW = panelW - 2 * PADDING;
        int lh = this.font.lineHeight;

        titleLines = this.font.split(this.title, innerW);
        bodyLines = this.font.split(body, innerW);

        int y = PADDING;
        titleRelY = y;  y += titleLines.size() * (lh + LINE_GAP) + TITLE_SEP_GAP;
        bodyRelY = y;   y += bodyLines.size() * (lh + LINE_GAP) + BODY_BUTTON_GAP;
        int buttonRelY = y; y += BUTTON_H + PADDING;
        panelH = y;

        panelX = (this.width - panelW) / 2;
        panelY = Math.max(0, (this.height - panelH) / 2);

        int buttonW = (innerW - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(PaymentConfirmCopy.OPEN), b -> respond(true))
                .bounds(panelX + PADDING, panelY + buttonRelY, buttonW, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable(PaymentConfirmCopy.CANCEL), b -> respond(false))
                .bounds(panelX + PADDING + buttonW + BUTTON_GAP, panelY + buttonRelY, buttonW, BUTTON_H).build());

        // The destination stays one click away for anyone who can't open a browser from here: a
        // quiet glyph in the corner, not a third choice beside Open and Cancel.
        CopyLinkIconButton copyLink = new CopyLinkIconButton(
                panelX + panelW - 4 - COPY_ICON, panelY + 4, COPY_ICON,
                Component.translatable(PaymentConfirmCopy.COPY_LINK), b -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(url);
                    b.setTooltip(Tooltip.create(Component.translatable(PaymentConfirmCopy.COPIED)));
                });
        copyLink.setTooltip(Tooltip.create(Component.translatable(PaymentConfirmCopy.COPY_LINK)));
        addRenderableWidget(copyLink);
    }

    private void respond(boolean open) {
        if (responded) return;
        responded = true;
        UiAnalytics.confirm(surface, target, open);
        if (open) {
            Util.getPlatform().openUri(URI.create(url));
        }
        Minecraft.getInstance().setScreen(parent);
    }

    /** ESC is a Cancel: recorded as {@code confirm_no}, back to the calling screen. */
    @Override
    public void onClose() {
        respond(false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        drawFrame(g, panelX, panelY, panelX + panelW, panelY + panelH);
        int cx = panelX + panelW / 2;
        int lh = this.font.lineHeight;
        int y = panelY + titleRelY;
        for (FormattedCharSequence line : titleLines) {
            g.drawCenteredString(this.font, line, cx, y, COLOUR_TITLE);
            y += lh + LINE_GAP;
        }
        int sepY = y + (TITLE_SEP_GAP / 2) - 1;
        g.fill(panelX + 10, sepY, panelX + panelW - 10, sepY + 1, COLOUR_SEPARATOR);
        y = panelY + bodyRelY;
        for (FormattedCharSequence line : bodyLines) {
            g.drawCenteredString(this.font, line, cx, y, COLOUR_BODY);
            y += lh + LINE_GAP;
        }
    }

    private static void drawFrame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, FRAME_BG);
        g.fill(x0, y0, x1, y0 + 1, FRAME_BORDER_TOP);
        g.fill(x0, y1 - 1, x1, y1, FRAME_BORDER_BOTTOM);
        g.fillGradient(x0, y0 + 1, x0 + 1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);
        g.fillGradient(x1 - 1, y0 + 1, x1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);
    }
}
