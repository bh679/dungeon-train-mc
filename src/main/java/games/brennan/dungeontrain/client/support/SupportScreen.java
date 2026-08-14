package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.localization.LocalizationCreditRegistry;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.ModList;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * "Ways to Help" hub, opened from the title-screen support button (see
 * {@link TitleScreenSupportButton}). A full-screen panel organised into the
 * three ways a player can support Dungeon Train:
 *
 * <ol>
 *   <li><b>Financial Support</b> — the three named price points ({@link SupportTier})
 *       on one row, with Custom Amount (Revolut) and Recurring (Patreon) beneath
 *       them, and the Kinetic Hosting affiliate (the referral shipped in the
 *       modpack's {@code khi.toml}) as an inline link. See {@link #financialRows()}
 *       for who sees which row.</li>
 *   <li><b>Share the Mod</b> — text only.</li>
 *   <li><b>Feedback &amp; Testing</b> — text only, with an inline clickable
 *       "Discord" link ("Join the Discord") to get involved.</li>
 *   <li><b>Help Translate</b> — a button into the in-game translation editor
 *       ({@link games.brennan.dungeontrain.client.localization.edit.TranslationScreen}), shown
 *       only to the players it is addressed to: those running a language the mod ships
 *       machine-translated and no human has worked through yet. See {@link #translateTarget()}.</li>
 * </ol>
 *
 * <p>Both the coloured buttons and the inline "Discord" links open their URL
 * through vanilla's {@link ConfirmLinkScreen} — the standard "open external
 * link?" confirmation. Confirming or cancelling returns to <em>this</em> hub
 * (not the title screen) so the player can follow several links in one visit.
 * Inline links are hit-tested in {@link #mouseClicked} against the wrapped
 * description lines.</p>
 *
 * <p>Section headers and descriptions are drawn in {@link #render} over a
 * translucent contrast panel painted in {@link #renderBackground}; the Financial
 * buttons are real widgets added in {@link #init} so they draw on top of that
 * panel.</p>
 */
public final class SupportScreen extends Screen {

    // All four outbound links come from OfficialLinks — relay-served when reachable, baked
    // fallbacks offline. Read in init(), which reruns on every open, so a fetch that lands
    // after the title screen built still applies here.

    private static final int MAX_COL_W  = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int BUTTON_H   = 20;
    /**
     * Height of the secondary row — Custom Amount and Recurring. Deliberately short: these are the
     * fallbacks for people the named price points don't fit, and at the tiers' own height they read
     * as a fourth and fifth equally-weighted choice, which is the opposite of the point.
     */
    private static final int SMALL_BUTTON_H = 16;
    /** How much of the column the secondary row spans (the rest is margin, so it reads as narrower). */
    private static final int SMALL_ROW_NUM = 1, SMALL_ROW_DEN = 2;
    private static final int BUTTON_GAP = 6;
    private static final int SECTION_GAP = 8;
    private static final int HEADER_GAP = 3;
    private static final int DESC_GAP   = 4;
    private static final int TOP        = 16;
    private static final int PANEL_PAD  = 10;

    private static final int COLOUR_PANEL  = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC   = 0xFFCACACA;
    /** Blue used for the inline "Discord" links (RGB, no alpha). */
    private static final int COLOUR_LINK   = 0x5B9BFF;

    /** Sprite tints (multiplied over the grey button sprite). */
    private static final float[] TINT_ORANGE = {1.00F, 0.47F, 0.38F}; // Patreon
    private static final float[] TINT_GREEN  = {0.30F, 0.80F, 0.35F}; // Direct donation
    private static final float[] TINT_BLUE   = {0.35F, 0.62F, 1.00F}; // China payment — distinct from the green direct-donation button

    private final Screen parent;

    // Computed in init(), consumed in render().
    private int colX;
    private int colW;
    private int panelTop;
    private int panelBottom;
    private int subtitleY;
    private List<FormattedCharSequence> subtitleLines = List.of();
    private final List<TextBlock> textBlocks = new ArrayList<>();

    /** A section's non-widget text: header line + wrapped description, with their Y positions. */
    private record TextBlock(Component header, int headerY, List<FormattedCharSequence> descLines, int descY) {}

    /**
     * One link button in a section: label key + target URL + optional sprite tint
     * (null = default grey) + optional hover-tooltip key (null = none) + the
     * analytics target name ({@link UiAnalytics} enum) for click/confirm events.
     *
     * <p>{@code action} is the escape hatch for the one button that does not leave the game: when
     * it is set the button runs it instead of opening {@code url} (which is then null), skipping
     * the {@link ConfirmLinkScreen} that only makes sense for an outbound link. Every other row
     * uses the five-argument constructor and behaves exactly as before.</p>
     */
    private record LinkButton(String labelKey, String url, float[] tint, String tooltipKey,
                              String analyticsTarget, Runnable action) {

        LinkButton(String labelKey, String url, float[] tint, String tooltipKey, String analyticsTarget) {
            this(labelKey, url, tint, tooltipKey, analyticsTarget, null);
        }
    }

    /**
     * One row of buttons in a section, with its own height and its own share of the column width —
     * the two knobs that let the secondary row be visibly secondary rather than just later.
     * A row narrower than the column is centred within it.
     */
    private record ButtonRow(List<LinkButton> buttons, int height, int width) {

        /** A full-width row at the standard button height — every row that isn't being demoted. */
        static ButtonRow primary(List<LinkButton> buttons, int colW) {
            return new ButtonRow(buttons, BUTTON_H, colW);
        }

        /** A short, narrow row: the open-ended options sitting under the price points. */
        static ButtonRow secondary(List<LinkButton> buttons, int colW) {
            return new ButtonRow(buttons, SMALL_BUTTON_H, colW * SMALL_ROW_NUM / SMALL_ROW_DEN);
        }
    }

    /** When this visit started — set once in the constructor (init() reruns on every resize). */
    private final long openedAtMs = System.currentTimeMillis();
    /** One-shot latch so Esc + Done + any future double-close report a single page_time. */
    private boolean timeReported;

    public SupportScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.support.title"));
        this.parent = parent;
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_SUPPORT_PAGE);
    }

    /**
     * Companion mods that ship via the Dungeon Train modpack but are not DT
     * dependencies (the sibling mods always load, so they can't discriminate).
     * Two or more of these present is read as "the player is running the
     * modpack" and switches the copy from mod- to modpack-wording.
     */
    private static final String[] MODPACK_COMPANIONS =
            {"appleskin", "ferritecore", "modernfix", "advancementplaques"};

    /** Heuristic: is this a modpack install rather than the bare mod? */
    private static boolean isModpackInstall() {
        int found = 0;
        for (String id : MODPACK_COMPANIONS) {
            if (ModList.get().isLoaded(id) && ++found >= 2) return true;
        }
        return false;
    }

    /**
     * Resolve a copy key to its {@code .modpack} variant when this looks like a
     * modpack install and the current language actually has that variant —
     * otherwise the base key, so untranslated languages fall back cleanly.
     *
     * <p>The {@code .cn} variant is applied first, so the two suffixes compose:
     * {@code desc.cn.modpack} → {@code desc.cn} → {@code desc.modpack} → {@code desc}.
     * Each step is guarded by {@link I18n#exists}, so only the languages that
     * actually author a variant ever see it.</p>
     */
    private static String copyKey(String baseKey) {
        String base = cnKey(baseKey);
        String modpackKey = base + ".modpack";
        return isModpackInstall() && I18n.exists(modpackKey) ? modpackKey : base;
    }

    /**
     * The {@code .cn} copy variant for clients that aren't shown Patreon — the base copy names it,
     * so leaving that copy in place would advertise a button that isn't on the screen.
     *
     * <p>The condition is "is Patreon absent", not "is this a Chinese client", and the two stopped
     * being the same thing when the price points landed: with tiers available only Simplified
     * Chinese loses the Patreon slot ({@link PaymentLinks#hidesOpenEndedOptions()}), while the
     * pre-tier layout still hands the whole {@code zh} family the China button instead
     * ({@link PaymentLinks#useChinaPayment()}). Asking the question the copy actually cares about
     * keeps both paths honest.</p>
     */
    private static String cnKey(String baseKey) {
        String cn = baseKey + ".cn";
        return patreonHidden() && I18n.exists(cn) ? cn : baseKey;
    }

    /** Whether the Financial section omits Patreon, under whichever layout is in force. */
    private static boolean patreonHidden() {
        return PaymentLinks.tiersAvailable()
                ? PaymentLinks.hidesOpenEndedOptions()
                : PaymentLinks.useChinaPayment();
    }

    @Override
    protected void init() {
        textBlocks.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = TOP;
        panelTop = y - PANEL_PAD;

        // Title is drawn centred at TOP in render(); reserve its line here.
        y += lh + 6;

        subtitleLines = this.font.split(Component.translatable("gui.dungeontrain.support.subtitle"), colW);
        subtitleY = y;
        y += subtitleLines.size() * lh + 8;

        // Financial — the three named price points, then the open-ended options under them (see
        // financialRows). The affiliate is an inline link spliced into the copy.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.financial.header"),
                Component.translatable(copyKey("gui.dungeontrain.support.financial.desc"), affiliateLink()),
                financialRows(colW));

        // Share — text only, no link.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.share.header"),
                Component.translatable(copyKey("gui.dungeontrain.support.share.desc")),
                List.of());

        // Feedback — only the final "Discord" (Join the Discord) is a clickable link.
        y = addSection(y, lh,
                Component.translatable("gui.dungeontrain.support.feedback.header"),
                Component.translatable(copyKey("gui.dungeontrain.support.feedback.desc"), discordLink()),
                List.of());

        // Translate — last, and only for the players it is addressed to (see translateTarget). Last
        // rather than woven in among the others so the three existing headers keep their numbers,
        // and so a language that is already reviewed simply sees the page it saw before.
        String translateTarget = translateTarget();
        if (!translateTarget.isEmpty()) {
            y = addSection(y, lh,
                    Component.translatable("gui.dungeontrain.support.translate.header"),
                    Component.translatable(copyKey("gui.dungeontrain.support.translate.desc")),
                    List.of(ButtonRow.primary(List.of(translateButton(translateTarget)), colW)));
        }

        panelBottom = y + (PANEL_PAD - SECTION_GAP);

        // Done flows just below the content panel so it can never collide with a
        // section's content, regardless of screen height / GUI scale.
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, panelBottom + 8, 200, 20)
                .build());
    }

    /**
     * Lay out one section (header text + wrapped description + zero or more rows of link buttons),
     * registering the text for {@link #render} and the buttons as live widgets. Returns the Y just
     * below the section.
     *
     * <p>Each row divides the column width among its own buttons, so a three-button row and a
     * two-button row stack without either having to know about the other — which is what lets the
     * Financial section put price points above the open-ended options, and lets the second row
     * simply not exist for the clients that don't get it.</p>
     */
    private int addSection(int y, int lh, Component header, Component desc, List<ButtonRow> rows) {
        int headerY = y;
        y += lh + HEADER_GAP;

        List<FormattedCharSequence> descLines = this.font.split(desc, colW);
        int descY = y;
        y += descLines.size() * lh + DESC_GAP;

        textBlocks.add(new TextBlock(header, headerY, descLines, descY));

        boolean anyRow = false;
        for (ButtonRow row : rows) {
            int n = row.buttons().size();
            if (n == 0) continue;
            int rowW = Math.min(row.width(), colW);
            int rowX = colX + (colW - rowW) / 2;
            int each = (rowW - (n - 1) * BUTTON_GAP) / n;
            for (int i = 0; i < n; i++) {
                int bx = rowX + i * (each + BUTTON_GAP);
                addRenderableWidget(makeLinkButton(bx, y, each, row.height(), row.buttons().get(i)));
            }
            y += row.height() + BUTTON_GAP;
            anyRow = true;
        }
        // Only undo the trailing inter-row gap if a row was actually laid out — a text-only section
        // (and one whose rows all came back empty) must not pull the next section upward.
        return (anyRow ? y - BUTTON_GAP : y) + SECTION_GAP;
    }

    /** Build a link button, tinted to {@link LinkButton#tint()} when set, else the default grey. */
    private Button makeLinkButton(int x, int y, int w, int h, LinkButton lb) {
        Component label = Component.translatable(lb.labelKey());
        Button.OnPress onPress = lb.action() != null
                ? b -> runAction(lb.action(), lb.analyticsTarget())
                : b -> openLink(lb.url(), lb.analyticsTarget());
        float[] t = lb.tint();
        Button button = (t == null)
                ? new DarkTintedButton(x, y, w, h, label, onPress)
                : new ColorTintedButton(x, y, w, h, label, t[0], t[1], t[2], onPress);
        if (lb.tooltipKey() != null) {
            button.setTooltip(Tooltip.create(Component.translatable(lb.tooltipKey())));
        }
        return button;
    }

    /**
     * The Financial section's button rows: the three named price points, then the open-ended
     * options — Custom Amount (Revolut) and Recurring (Patreon) — underneath them.
     *
     * <p>The second row is dropped entirely for Simplified-Chinese clients, where both providers are
     * walled: showing a button that cannot complete is worse than showing one fewer. The price
     * points stay, because they run through Stripe, which does work there — so that audience keeps a
     * working donation route without needing the separate WeChat button the pre-tier layout gave
     * them.</p>
     *
     * <p>When the relay hasn't served a checkout route ({@link PaymentLinks#tiersAvailable()}) this
     * returns the exact layout that shipped before price points existed — see
     * {@link #preTierRow()}.</p>
     */
    private static List<ButtonRow> financialRows(int colW) {
        if (!PaymentLinks.tiersAvailable()) return List.of(ButtonRow.primary(preTierRow(), colW));

        List<LinkButton> tiers = new ArrayList<>();
        for (SupportTier tier : SupportTier.values()) {
            tiers.add(new LinkButton(tier.labelKey(), PaymentLinks.tierUrl(tier), TINT_BLUE,
                    tier.tooltipKey(), tier.analyticsTarget()));
        }
        ButtonRow tierRow = ButtonRow.primary(tiers, colW);
        if (PaymentLinks.hidesOpenEndedOptions()) return List.of(tierRow);

        // Short and narrow: the answer for people the named amounts don't suit, not a fourth option
        // competing with them. Their tooltips still explain the difference on hover.
        return List.of(tierRow, ButtonRow.secondary(List.of(
                new LinkButton("gui.dungeontrain.support.financial.custom_amount", revolutUrl(), TINT_GREEN,
                        "gui.dungeontrain.support.donate_tooltip", UiAnalytics.TARGET_DONATE),
                new LinkButton("gui.dungeontrain.support.financial.recurring", OfficialLinks.patreon(),
                        TINT_ORANGE, "gui.dungeontrain.support.recurring_tooltip", UiAnalytics.TARGET_PATREON)), colW));
    }

    /**
     * The pre-price-point layout, kept intact for the offline / relay-unreachable case: a Direct
     * Donation button (Revolut) then either Patreon or, where Patreon is blocked, the China payment
     * route. The China tooltip differs because that route runs through Stripe and so is not fee-free
     * the way the direct Revolut donation is.
     */
    private static List<LinkButton> preTierRow() {
        LinkButton donate = new LinkButton("gui.dungeontrain.support.financial.donate", revolutUrl(),
                TINT_GREEN, "gui.dungeontrain.support.donate_tooltip", UiAnalytics.TARGET_DONATE);
        LinkButton second = PaymentLinks.useChinaPayment()
                ? new LinkButton("gui.dungeontrain.support.financial.cn_donate", PaymentLinks.chinaUrl(),
                        TINT_BLUE, "gui.dungeontrain.support.cn_donate_tooltip", UiAnalytics.TARGET_DONATE_CN)
                : new LinkButton("gui.dungeontrain.support.financial.patreon", OfficialLinks.patreon(),
                        TINT_ORANGE, null, UiAnalytics.TARGET_PATREON);
        return List.of(donate, second);
    }

    /**
     * The language the Translate section should offer, or {@code ""} to leave the section out.
     *
     * <p>Two questions, both already answered elsewhere. {@link TranslationTarget#resolveForClient()}
     * gives the language this client could edit at all — the player's own, or nothing on English
     * (a dev build points at its dev target instead, which is what makes this section testable
     * without switching the whole game to Chinese). Then {@link LocalizationCreditRegistry
     * #isHumanReviewed} asks whether it still needs the help: a locale a translator has worked
     * through is not one to keep nagging on the page about ways to help. That check already folds
     * in the approvals this client has downloaded since the build was cut, so a language volunteers
     * finish off stops advertising the section without waiting for a release.</p>
     */
    private static String translateTarget() {
        String target = TranslationTarget.resolveForClient();
        if (target.isEmpty() || LocalizationCreditRegistry.isHumanReviewed(target)) {
            return "";
        }
        return target;
    }

    /**
     * The Translate section's single button: straight into the editor for {@code target}, with this
     * hub as its parent so closing the editor comes back here rather than to the title screen.
     */
    private LinkButton translateButton(String target) {
        // Default grey, not one of the payment tints: this is the one button on the page that does
        // not ask for money, and colouring it like the ones that do would misread.
        return new LinkButton("gui.dungeontrain.translate.button", null, null,
                "gui.dungeontrain.support.translate.tooltip", UiAnalytics.TARGET_TRANSLATE,
                () -> Minecraft.getInstance().setScreen(new TranslationScreen(this, target)));
    }

    /** @see PaymentLinks#donateUrl() */
    private static String revolutUrl() {
        return PaymentLinks.donateUrl();
    }

    /** A clickable, blue, underlined "Discord" word for splicing into description copy. */
    private Component discordLink() {
        return link(Component.literal("Discord"), OfficialLinks.discord());
    }

    /** A clickable, blue, underlined "affiliate link" phrase for the Financial copy. */
    private Component affiliateLink() {
        return link(Component.translatable("gui.dungeontrain.support.financial.affiliate_link"), OfficialLinks.affiliate());
    }

    /** Style {@code label} as a blue, underlined, click-to-open-URL inline link. */
    private static Component link(net.minecraft.network.chat.MutableComponent label, String url) {
        return label.withStyle(s -> s
                .withColor(COLOUR_LINK)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

    /**
     * Open {@code url} through the vanilla confirm screen, recording the click and whether the
     * player followed through. {@code analyticsTarget} is one of the {@link UiAnalytics} target
     * names, or null to open untracked (an inline URL we don't recognise).
     */
    private void openLink(String url, String analyticsTarget) {
        // The price-point URLs are built from a relay-served base that a late fetch can in principle
        // change between init() and the click. Swallowing that is right: the alternative is an NPE
        // in front of someone trying to donate, and the next screen open rebuilds the buttons.
        if (url == null) return;
        if (analyticsTarget != null) {
            UiAnalytics.click(UiAnalytics.SURFACE_SUPPORT_PAGE, analyticsTarget);
        }
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (analyticsTarget != null) {
                UiAnalytics.confirm(UiAnalytics.SURFACE_SUPPORT_PAGE, analyticsTarget, yes);
            }
            if (yes) {
                Util.getPlatform().openUri(URI.create(url));
            }
            // Return to the hub so the player can follow more than one link.
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /**
     * Run an in-game button's action, recording the click. The counterpart to {@link #openLink} for
     * the buttons that stay inside the game: no confirm prompt, because nothing is being opened
     * outside Minecraft, and so no confirm event either — the click is the whole funnel.
     */
    private void runAction(Runnable action, String analyticsTarget) {
        if (analyticsTarget != null) {
            UiAnalytics.click(UiAnalytics.SURFACE_SUPPORT_PAGE, analyticsTarget);
        }
        action.run();
    }

    /**
     * Analytics target name for an inline link's URL — the two inline links are built from
     * {@link OfficialLinks}, so an exact match identifies them; anything else is untracked.
     */
    private static String inlineTarget(String url) {
        if (url == null) return null;
        if (url.equals(OfficialLinks.discord())) return UiAnalytics.TARGET_DISCORD;
        if (url.equals(OfficialLinks.affiliate())) return UiAnalytics.TARGET_AFFILIATE;
        return null;
    }

    /** The clickable {@link Style} under the given mouse position, or null — used for inline links. */
    private Style styleAt(double mouseX, double mouseY) {
        int lh = this.font.lineHeight;
        for (TextBlock tb : textBlocks) {
            for (int i = 0; i < tb.descLines().size(); i++) {
                int lineY = tb.descY() + i * lh;
                if (mouseY < lineY || mouseY >= lineY + lh) continue;
                FormattedCharSequence line = tb.descLines().get(i);
                if (mouseX < colX || mouseX >= colX + this.font.width(line)) continue;
                return this.font.getSplitter().componentStyleAtWidth(line, (int) (mouseX - colX));
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Style style = styleAt(mouseX, mouseY);
            if (style != null && style.getClickEvent() != null
                    && style.getClickEvent().getAction() == ClickEvent.Action.OPEN_URL) {
                String url = style.getClickEvent().getValue();
                openLink(url, inlineTarget(url));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Blurred menu panorama (vanilla), then a translucent panel behind the
        // content column so text stays readable over the spinning background.
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, panelTop, colX + colW + PANEL_PAD, panelBottom, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draws the background (with our panel) and the button widgets.
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.drawCenteredString(this.font, this.title, this.width / 2, TOP, COLOUR_HEADER);

        for (int i = 0; i < subtitleLines.size(); i++) {
            FormattedCharSequence line = subtitleLines.get(i);
            int lineX = this.width / 2 - this.font.width(line) / 2;
            g.drawString(this.font, line, lineX, subtitleY + i * lh, COLOUR_DESC, false);
        }

        for (TextBlock tb : textBlocks) {
            g.drawString(this.font, tb.header(), colX, tb.headerY(), COLOUR_HEADER, false);
            for (int i = 0; i < tb.descLines().size(); i++) {
                g.drawString(this.font, tb.descLines().get(i), colX, tb.descY() + i * lh, COLOUR_DESC, false);
            }
        }
    }

    @Override
    public void onClose() {
        if (!timeReported) {
            timeReported = true;
            UiAnalytics.pageTime(UiAnalytics.SURFACE_SUPPORT_PAGE, System.currentTimeMillis() - openedAtMs);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
