package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import games.brennan.dungeontrain.client.support.PaymentLinks;
import games.brennan.dungeontrain.client.support.SupportTier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;

/**
 * Full-screen "Contribute" window opened from the death-screen donation page's Contribute button.
 * Offers the three named price points ({@link SupportTier}) on one row, with the open-ended options
 * under them — Custom Amount (Revolut, name-tagged) and Recurring (Patreon). Each opens through
 * vanilla's {@link ConfirmLinkScreen} with support-funnel analytics, returning here so the player can
 * follow more than one. Closing returns to the death screen it was opened from.
 *
 * <p>Two clients see something different. Simplified-Chinese clients lose the open-ended row, since
 * Revolut and Patreon are both walled there and the Stripe-backed price points work regardless. And
 * a client whose relay never served a checkout route ({@link PaymentLinks#tiersAvailable()}) gets the
 * pre-price-point layout it always had: Revolut plus Patreon, or Revolut plus the China payment
 * route. Mirrors the Support page's Financial section — see
 * {@link games.brennan.dungeontrain.client.support.SupportScreen}.</p>
 *
 * <p>Styled to match the death screen: the same dark-blue backdrop, an amber title and muted
 * narration over the two coloured buttons. URLs come from {@link OfficialLinks} (relay-served,
 * baked fallbacks offline).</p>
 */
public final class DonationOptionsScreen extends Screen {

    // Match the death-screen backdrop (NarrativeDeathScreen.OVERLAY) for a seamless hand-off.
    private static final int OVERLAY = 0xF2070C1E;
    private static final int TITLE   = 0xFFE0B56A;
    private static final int NARR    = 0xFFC7BDA7;
    private static final float[] TINT_GREEN  = {0.30F, 0.80F, 0.35F}; // direct donation
    private static final float[] TINT_ORANGE = {1.00F, 0.47F, 0.38F}; // Patreon
    private static final float[] TINT_BLUE   = {0.35F, 0.62F, 1.00F}; // China payment — distinct from the green direct-donation button

    private final Screen parent;
    // Text positions computed in init(), drawn in render() (title + subtitle + a per-column
    // description above each button).
    private int titleY;
    private int subtitleTop;
    private int descTop;
    private int leftColCenter;
    private int rightColCenter;
    private List<FormattedCharSequence> subtitleLines = List.of();
    private List<FormattedCharSequence> revolutDescLines = List.of();
    private List<FormattedCharSequence> patreonDescLines = List.of();

    public DonationOptionsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.death.narr.donate_button"));
        this.parent = parent;
        // Distinct funnel step from "viewed the DONATE page": the player opened the Contribute window.
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.PAGE_CONTRIBUTE);
        OfficialLinks.ensureFetched();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int lineH = this.font.lineHeight + 2;

        int wrapW = Math.min(360, this.width - 80);
        subtitleLines = this.font.split(
                Component.translatable("gui.dungeontrain.death.narr.donate_options_sub"), wrapW);

        int rowW = Math.min(340, this.width - 60);
        int gap = 10;
        int rowX = cx - rowW / 2;
        int bh = 32;

        boolean tiers = PaymentLinks.tiersAvailable();
        // The open-ended options keep their descriptive line above the button; the price points do
        // not. At three across each column is ~106px, which fits roughly two words — a description
        // there would wrap into an unreadable stack, so those buttons carry tooltips instead.
        int modeEach = (rowW - gap) / 2;
        leftColCenter = rowX + modeEach / 2;
        rightColCenter = rowX + modeEach + gap + modeEach / 2;

        boolean cn = PaymentLinks.useChinaPayment();
        boolean showModes = !tiers || !PaymentLinks.hidesOpenEndedOptions();
        revolutDescLines = showModes
                ? this.font.split(Component.translatable("gui.dungeontrain.support.donate_tooltip"), modeEach - 4)
                : List.of();
        // Pre-tier the right column is Patreon-or-WeChat; with tiers it is always Recurring
        // (Patreon), because the audience that can't reach Patreon has no mode row at all.
        patreonDescLines = showModes
                ? this.font.split(Component.translatable(!tiers && cn
                        ? "gui.dungeontrain.death.narr.donate_cn_desc"
                        : "gui.dungeontrain.death.narr.donate_patreon_desc"), modeEach - 4)
                : List.of();
        int descRows = Math.max(revolutDescLines.size(), patreonDescLines.size());

        // Vertically centre the whole title → back block. The tier row (when present) sits above the
        // descriptions, so it adds its own height plus a gap.
        int tierRowH = tiers ? bh + gap : 0;
        int subH = subtitleLines.size() * lineH;
        int descH = descRows * lineH;
        int modeRowH = showModes ? descH + 4 + bh : 0;
        int blockH = lineH + 6 + subH + 12 + tierRowH + modeRowH + 12 + 20;
        int top = Math.max(20, (this.height - blockH) / 2);

        titleY = top;
        subtitleTop = titleY + lineH + 6;
        int y = subtitleTop + subH + 12;

        if (tiers) {
            int tierEach = (rowW - gap * (SupportTier.values().length - 1)) / SupportTier.values().length;
            int i = 0;
            for (SupportTier tier : SupportTier.values()) {
                Button b = new ColorTintedButton(rowX + i * (tierEach + gap), y, tierEach, bh,
                        Component.translatable(tier.labelKey()),
                        TINT_BLUE[0], TINT_BLUE[1], TINT_BLUE[2],
                        p -> openLink(PaymentLinks.tierUrl(tier), tier.analyticsTarget()));
                b.setTooltip(Tooltip.create(Component.translatable(tier.tooltipKey())));
                addRenderableWidget(b);
                i++;
            }
            y += bh + gap;
        }

        descTop = y;
        y += descH + 4;

        if (showModes) {
            // Left: the custom-amount route (Revolut, no fees). Right: Patreon — or, on the pre-tier
            // layout only, the China payment route where Patreon is unreachable.
            addRenderableWidget(new ColorTintedButton(rowX, y, modeEach, bh,
                    Component.translatable(tiers
                            ? "gui.dungeontrain.support.financial.custom_amount"
                            : "gui.dungeontrain.death.narr.donate_revolut"),
                    TINT_GREEN[0], TINT_GREEN[1], TINT_GREEN[2],
                    b -> openLink(revolutUrl(), UiAnalytics.TARGET_DONATE)));

            boolean rightIsCn = !tiers && cn;
            float[] rightTint = rightIsCn ? TINT_BLUE : TINT_ORANGE;
            String rightLabel = rightIsCn ? "gui.dungeontrain.death.narr.donate_cn"
                    : tiers ? "gui.dungeontrain.support.financial.recurring"
                            : "gui.dungeontrain.death.narr.donate_patreon";
            String rightUrl = rightIsCn ? PaymentLinks.chinaUrl() : OfficialLinks.patreon();
            String rightTarget = rightIsCn ? UiAnalytics.TARGET_DONATE_CN : UiAnalytics.TARGET_PATREON;
            addRenderableWidget(new ColorTintedButton(rowX + modeEach + gap, y, modeEach, bh,
                    Component.translatable(rightLabel),
                    rightTint[0], rightTint[1], rightTint[2],
                    b -> openLink(rightUrl, rightTarget)));
            y += bh;
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(cx - 100, y + 12, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // background + widgets
        int cx = this.width / 2;
        int lineH = this.font.lineHeight + 2;
        g.drawCenteredString(this.font, this.title, cx, titleY, TITLE);
        int y = subtitleTop;
        for (FormattedCharSequence line : subtitleLines) {
            g.drawString(this.font, line, cx - this.font.width(line) / 2, y, NARR, false);
            y += lineH;
        }
        // Per-column descriptions, sitting directly above their button.
        drawColumnDesc(g, revolutDescLines, leftColCenter, lineH);
        drawColumnDesc(g, patreonDescLines, rightColCenter, lineH);
    }

    /** Draw a wrapped description block centred on {@code centerX}, anchored at {@link #descTop}. */
    private void drawColumnDesc(GuiGraphics g, List<FormattedCharSequence> lines, int centerX, int lineH) {
        int y = descTop;
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, centerX - this.font.width(line) / 2, y, NARR, false);
            y += lineH;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, OVERLAY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Open {@code url} through the confirm screen, tracking the click + follow-through, then return here. */
    private void openLink(String url, String analyticsTarget) {
        UiAnalytics.click(UiAnalytics.SURFACE_DEATH_SCREEN, analyticsTarget);
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_DEATH_SCREEN, analyticsTarget, yes);
            if (yes) Util.getPlatform().openUri(URI.create(url));
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /** @see PaymentLinks#donateUrl() */
    private static String revolutUrl() {
        return PaymentLinks.donateUrl();
    }
}
