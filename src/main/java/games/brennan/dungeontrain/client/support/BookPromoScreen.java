package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
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
 * "Promote a Book" page — buying a community book a better place in the shared pool. Opened from the
 * title screen's dev-only icon (see {@code client.TitleScreenBookPromoButton}); the entry point does
 * not exist on a release build, so neither does this page for players.
 *
 * <p>One row of three price points ({@link BookPromoTier}), each drawn as the books it buys
 * ({@link BookPromoTierButton}) and opening the relay's checkout route through vanilla's
 * {@link ConfirmLinkScreen}, returning <em>here</em> afterwards so a player who backs out of one can
 * pick another. Structurally a slimmed-down {@link SupportScreen}: the same translucent contrast
 * panel, the same wrapped-copy-over-panel layout and the same confirm-and-come-back link flow — this
 * is a second instance of that surface, not a new pattern.</p>
 *
 * <p><b>When the relay has served no checkout route</b> ({@link PaymentLinks#tiersAvailable()} —
 * offline, or the key is unset) the three buttons render <b>disabled</b> under an explanatory line
 * rather than the page hiding them. That is the opposite of what {@link SupportScreen} does, on
 * purpose: there the tiers are one option among several and a quiet degrade to the open-ended
 * buttons is the kindest answer, whereas here they are the entire page, and a dev opening it needs to
 * see that the route is missing rather than an empty panel.</p>
 *
 * <p><b>This page sells the checkout, not the fulfilment.</b> A completed payment is tagged with the
 * player's name exactly as a donation is; nothing yet raises a specific book's pool weight
 * automatically, and the game has no client-side registry of "books I wrote" to choose from. Both are
 * relay-side work — keep the copy honest about what is being bought until they land.</p>
 */
public final class BookPromoScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int BUTTON_GAP = 6;
    private static final int TOP = 16;
    private static final int PANEL_PAD = 10;
    private static final int TEXT_GAP = 8;

    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC = 0xFFCACACA;
    /** Muted amber for the "checkout unavailable" line — a notice, not an error the player caused. */
    private static final int COLOUR_NOTICE = 0xFFE0B56A;

    private final Screen parent;

    // Computed in init(), consumed in render().
    private int colX;
    private int colW;
    private int panelTop;
    private int panelBottom;
    private int subtitleY;
    private int noticeY = -1;
    private List<FormattedCharSequence> subtitleLines = List.of();
    private List<FormattedCharSequence> noticeLines = List.of();

    /** When this visit started — set once here (init() reruns on every resize). */
    private final long openedAtMs = System.currentTimeMillis();
    /** One-shot latch so Esc + Done report a single page_time. */
    private boolean timeReported;

    public BookPromoScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.book_promo.title"));
        this.parent = parent;
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_BOOK_PROMO_PAGE);
        // A late fetch still helps: init() reads the checkout route on every open, so a route that
        // lands while this page is up is picked up the next time it is built.
        OfficialLinks.ensureFetched();
    }

    @Override
    protected void init() {
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = TOP;
        panelTop = y - PANEL_PAD;

        // Title is drawn centred at TOP in render(); reserve its line here.
        y += lh + 6;

        subtitleLines = this.font.split(Component.translatable("gui.dungeontrain.book_promo.subtitle"), colW);
        subtitleY = y;
        y += subtitleLines.size() * lh + TEXT_GAP;

        boolean available = PaymentLinks.tiersAvailable();
        if (available) {
            noticeLines = List.of();
            noticeY = -1;
        } else {
            noticeLines = this.font.split(
                    Component.translatable("gui.dungeontrain.book_promo.unavailable"), colW);
            noticeY = y;
            y += noticeLines.size() * lh + TEXT_GAP;
        }

        BookPromoTier[] tiers = BookPromoTier.values();
        int each = (colW - (tiers.length - 1) * BUTTON_GAP) / tiers.length;
        for (int i = 0; i < tiers.length; i++) {
            addRenderableWidget(tierButton(tiers[i], colX + i * (each + BUTTON_GAP), y, each, available));
        }
        y += BookPromoTierButton.HEIGHT;

        panelBottom = y + PANEL_PAD;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, panelBottom + 8, 200, 20)
                .build());
    }

    /**
     * One price point, drawn as its books ({@link BookPromoTierButton}) over the price label.
     * Inactive — but still visible, still explaining itself — when there is no checkout route.
     */
    private Button tierButton(BookPromoTier tier, int x, int y, int w, boolean available) {
        BookPromoTierButton button = new BookPromoTierButton(x, y, w,
                Component.translatable(tier.labelKey()), tier.quantity(),
                b -> openCheckout(tier));
        button.setTooltip(Tooltip.create(Component.translatable(tier.tooltipKey())));
        button.active = available;
        return button;
    }

    /** Open {@code tier}'s checkout through the vanilla confirm screen, recording both funnel steps. */
    private void openCheckout(BookPromoTier tier) {
        String url = tier.url();
        // Built from a relay-served base that a late fetch can in principle change between init() and
        // the click. Swallowing that beats an NPE in front of someone trying to pay; reopening the
        // page rebuilds the buttons against whatever the relay is serving by then.
        if (url == null) return;
        UiAnalytics.click(UiAnalytics.SURFACE_BOOK_PROMO_PAGE, tier.analyticsTarget());
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_BOOK_PROMO_PAGE, tier.analyticsTarget(), yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            // Back to this page, not the title screen — backing out of one tier should leave the
            // other two in reach.
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then a translucent panel behind the content column so the
        // text stays readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, panelTop, colX + colW + PANEL_PAD, panelBottom, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.drawCenteredString(this.font, this.title, this.width / 2, TOP, COLOUR_HEADER);

        for (int i = 0; i < subtitleLines.size(); i++) {
            FormattedCharSequence line = subtitleLines.get(i);
            g.drawString(this.font, line, this.width / 2 - this.font.width(line) / 2,
                    subtitleY + i * lh, COLOUR_DESC, false);
        }

        for (int i = 0; i < noticeLines.size(); i++) {
            FormattedCharSequence line = noticeLines.get(i);
            g.drawString(this.font, line, this.width / 2 - this.font.width(line) / 2,
                    noticeY + i * lh, COLOUR_NOTICE, false);
        }
    }

    @Override
    public void onClose() {
        if (!timeReported) {
            timeReported = true;
            UiAnalytics.pageTime(UiAnalytics.SURFACE_BOOK_PROMO_PAGE, System.currentTimeMillis() - openedAtMs);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
