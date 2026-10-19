package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.live.life.LiveRewards;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import games.brennan.dungeontrain.client.support.currency.CurrencyFormat;
import games.brennan.dungeontrain.narrative.PriorityBookMessage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The one Contribute chooser, laid out in a single column: three gift tiers, one featured community
 * reward with a See All Rewards button, and a quiet line for the open-ended options. Embedded by
 * {@link ContributeScreen} (what every "Support the Mod" button opens) and by the death screen's
 * {@code DonationOptionsScreen}, each on its own palette ({@link Style}).
 *
 * <p>Not a widget itself: the host calls {@link #layout} from its {@code init()} (widgets go through
 * the host's {@code addRenderableWidget}), {@link #render} from its {@code render()}, and
 * {@link #mouseClicked} for the footer's inline links. {@link #measure} answers the height first so
 * the host can centre the column.</p>
 *
 * <p>Every money link goes {@code UiAnalytics.click} then {@link PaymentConfirmScreen#tryOpen}; the
 * affiliate link keeps vanilla's confirm.</p>
 */
public final class ContributePanel {

    /** The two homes' looks. */
    public enum Style {
        /** The Contribute page: support-screen greys, the affiliate sentence in the footer. */
        PAGE(24, 0xFF8A8A8A, 0xFFCACACA, true),
        /** The death screen's Contribute window: the engine room's sepia, taller tiers, no affiliate. */
        DEATH(26, 0xFF8A7C60, 0xFFC7BDA7, false);

        final int tierH;
        final int label;
        final int text;
        final boolean affiliate;

        Style(int tierH, int label, int text, boolean affiliate) {
            this.tierH = tierH;
            this.label = label;
            this.text = text;
            this.affiliate = affiliate;
        }
    }

    static final float[] TINT_BLUE = {0.35F, 0.62F, 1.00F};
    static final float[] TINT_PURPLE = {0.72F, 0.50F, 1.00F};
    static final float[] TINT_GREEN = {0.30F, 0.80F, 0.35F};
    static final float[] TINT_ORANGE = {1.00F, 0.47F, 0.38F};

    private static final int GAP = 6;
    private static final int LABEL_GAP = 4;
    private static final int CARD_PAD = 6;
    private static final int GIVE_W = 88;
    private static final int GIVE_H = 20;
    private static final int SMALL_H = 16;

    private static final int COLOUR_WHITE = 0xFFFFFFFF;
    private static final int COLOUR_YOU = 0xFF8FE08A;
    private static final int COLOUR_ON = 0xFF8FE08A;
    private static final int COLOUR_REWARDS_TITLE = 0xB99BF0;
    private static final int COLOUR_GIVE_NOTE = 0xFFF0B35A;
    private static final int COLOUR_CARD = 0x306D4B9C;
    private static final int COLOUR_CARD_EDGE = 0xFF4A3F66;
    private static final int COLOUR_FEATURED_EDGE = 0xFF8FE08A;
    private static final int COLOUR_LINK = 0x5B9BFF;

    /** Footer link markers, carried as a click event the panel resolves itself (never followed raw). */
    private static final String LINK_DONATE = "dungeontrain:contribute/donate";
    private static final String LINK_PATREON = "dungeontrain:contribute/patreon";
    private static final String LINK_AFFILIATE = "dungeontrain:contribute/affiliate";

    private final Screen host;
    private final String surface;
    private final Style style;
    /** The host's rebuildWidgets(), for when the Priority Books count arrives after layout. */
    private final Runnable relayout;

    /** One run of text, drawn at an absolute position. */
    private record Text(FormattedCharSequence line, int x, int y, int colour, boolean centred) {}

    private final List<Text> texts = new ArrayList<>();
    private final List<FormattedCharSequence> footerLines = new ArrayList<>();
    private int footerX, footerY, footerW;
    private int cardX, cardY, cardW, cardH;
    /** The card's own text, drawn clipped to the card so it can slide. */
    private final List<Text> cardTexts = new ArrayList<>();
    /** Index into {@link CommunityRewards#FEATURE_ORDER} the arrows moved to; -1 = the featured one. */
    private int pick = -1;
    /** Direction of the last arrow press (-1 left, 1 right) and when, for the slide. */
    private int slideDir;
    private long slideStartMs;
    @javax.annotation.Nullable private Button giveOnCard;
    private static final String LEFT = "\u25C0";
    private static final String RIGHT = "\u25B6";
    /** Gap between the card's edge and an arrow; the hit area pads the glyph by this much too. */
    private static final int ARROW_GAP = 5;
    private static final int COLOUR_ARROW = 0xFFB9A8E0;
    private static final int COLOUR_ARROW_HOVER = 0xFFFFFFFF;
    private int arrowY, leftArrowX, rightArrowX, arrowCurrent;
    private static final long SLIDE_MS = 180;
    private static final int ICON = 16;
    private static final int ICON_GAP = 5;
    private net.minecraft.world.item.ItemStack cardIcon = net.minecraft.world.item.ItemStack.EMPTY;
    private int cardIconX, cardIconY;
    /** The middle tier's bounds, outlined green like the website's featured tier; w 0 = none. */
    private int featX, featY, featW, featH;

    public ContributePanel(Screen host, String surface, Style style, Runnable relayout) {
        this.host = host;
        this.surface = surface;
        this.style = style;
        this.relayout = relayout;
    }

    /** Height {@link #layout} will take at width {@code w}, without placing anything. */
    public int measure(Font font, int w) {
        return run(font, 0, 0, w, null);
    }

    /** Lay the panel out at ({@code x}, {@code y}), adding its buttons through {@code add}. Returns its height. */
    public int layout(Font font, int x, int y, int w, Consumer<AbstractWidget> add) {
        BookCredits.refresh(() -> {
            if (Minecraft.getInstance().screen == host) relayout.run();
        });
        return run(font, x, y, w, add);
    }

    private int run(Font font, int x, int y, int w, Consumer<AbstractWidget> add) {
        boolean commit = add != null;
        if (commit) {
            texts.clear();
            cardTexts.clear();
            giveOnCard = null;
            footerLines.clear();
            featW = 0;
        }
        int lh = font.lineHeight;
        int top = y;

        // The thank-you this player already has waiting: "3 Priority Books waiting".
        int books = BookCredits.current();
        if (books > 0) {
            for (FormattedCharSequence l : font.split(PriorityBookMessage.waiting(locale(), books), w)) {
                if (commit) texts.add(new Text(l, x + w / 2, y, COLOUR_YOU, true));
                y += lh + 1;
            }
            y += GAP;
        }

        boolean tiers = PaymentLinks.tiersAvailable();
        if (tiers) {
            y = label(font, Component.translatable("gui.dungeontrain.contribute.group.dev"), x, y, w, commit);
            SupportTier[] all = SupportTier.values();
            int each = (w - GAP * (all.length - 1)) / all.length;
            for (int i = 0; i < all.length; i++) {
                SupportTier tier = all[i];
                int bx = x + i * (each + GAP);
                if (commit) {
                    Button b = new ColorTintedButton(bx, y, each, style.tierH,
                            CurrencyFormat.amount(tier.audAmount(), tier.shortKey()),
                            TINT_BLUE[0], TINT_BLUE[1], TINT_BLUE[2],
                            p -> openLink(PaymentLinks.tierUrl(tier), tier.analyticsTarget()));
                    b.setTooltip(Tooltip.create(Component.translatable(tier.tooltipKey())));
                    add.accept(b);
                    FormattedCharSequence name = Component.translatable(tier.nameKey()).getVisualOrderText();
                    texts.add(new Text(name, bx + each / 2, y + style.tierH + 3, style.text, true));
                    if (i == all.length / 2) { featX = bx; featY = y; featW = each; featH = style.tierH; }
                }
            }
            y += style.tierH + 3 + lh + GAP + 2;
        } else {
            // No checkout route from the relay (offline, an old relay): the open-ended options become
            // the panel's main row, as the pre-tier layout always did.
            if (commit) {
                int each = (w - GAP) / 2;
                add.accept(openEndedButton(x, y, each, GIVE_H, true));
                add.accept(openEndedButton(x + each + GAP, y, each, GIVE_H, false));
            }
            y += GIVE_H + GAP + 2;
        }

        y = rewardCard(font, x, y, w, commit, add);

        if (commit) {
            Button seeAll = new ColorTintedButton(x, y, w, SMALL_H,
                    Component.translatable("gui.dungeontrain.contribute.see_all"),
                    TINT_PURPLE[0], TINT_PURPLE[1], TINT_PURPLE[2], p -> openRewards());
            add.accept(seeAll);
        }
        y += SMALL_H + GAP + 2;

        List<FormattedCharSequence> footer = font.split(footer(tiers), w);
        if (commit) {
            footerLines.addAll(footer);
            footerX = x;
            footerY = y;
            footerW = w;
        }
        y += footer.size() * (lh + 1);
        return y - top;
    }

    /** A small grey group label ("A GIFT TO THE DEV · YOUR NAME ABOARD"); returns the y under it. */
    private int label(Font font, Component text, int x, int y, int w, boolean commit) {
        for (FormattedCharSequence l : font.split(text, w)) {
            if (commit) texts.add(new Text(l, x, y, style.label, false));
            y += font.lineHeight;
        }
        return y + LABEL_GAP;
    }

    /**
     * The featured community reward: group label, name, its one line, an "ON until" line when it is
     * on, and a purple Give button with "For everyone" under it on the right.
     */
    private int rewardCard(Font font, int x, int y, int w, boolean commit, Consumer<AbstractWidget> add) {
        // "COMMUNITY REWARDS" in bold purple, then " · A GIFT TO EVERYONE" in the label grey.
        Component title = Component.literal(Component.translatable("gui.dungeontrain.rewards.title").getString()
                        .toUpperCase(javaLocale()))
                .withStyle(st -> st.withBold(true).withColor(COLOUR_REWARDS_TITLE));
        y = label(font, Component.empty().append(title).append(" · ")
                .append(Component.translatable("gui.dungeontrain.contribute.group.everyone")), x, y, w, commit);
        long now = System.currentTimeMillis();
        LiveRewards state = LiveRewards.latest();
        List<CommunityRewards.Reward> order = CommunityRewards.FEATURE_ORDER;
        CommunityRewards.Reward reward = pick >= 0 ? order.get(pick) : CommunityRewards.featured(state, now);
        int lh = font.lineHeight;

        Component noteText = Component.translatable("gui.dungeontrain.contribute.keeps_on");
        int sideW = Math.min(w / 3, Math.max(GIVE_W, font.width(noteText) + 2));
        // The reward's icon sits left of its name and line; the text starts after it.
        int iconLeft = x + CARD_PAD;
        int textX = iconLeft + ICON + ICON_GAP;
        int textW = w - sideW - CARD_PAD * 3 - ICON - ICON_GAP;
        int sideH = GIVE_H + 2 + lh;
        CardLines lines = cardLines(font, reward, state, now, textX, y + CARD_PAD, textW);
        int h = Math.max(lines.bottom() + CARD_PAD * 2 - 1, sideH + CARD_PAD * 2);

        if (commit) {
            cardTexts.addAll(lines.lines());
            cardIcon = RewardIcons.of(reward);
            cardIconX = iconLeft;
            cardIconY = y + CARD_PAD + Math.max(0, (lines.bottom() - ICON) / 2);
            cardX = x;
            cardY = y;
            cardW = w;
            cardH = h;
            int gx = x + w - sideW - CARD_PAD;
            int gy = y + (h - sideH) / 2;
            giveOnCard = giveButton(reward, CommunityRewards.active(state, reward, now), state, now,
                    gx, gy, sideW, GIVE_H, this, true);
            add.accept(giveOnCard);
            // "For everyone", under the button even while it reads Soon: it says what the reward is.
            for (FormattedCharSequence note : font.split(noteText, sideW)) {
                cardTexts.add(new Text(note, gx + sideW / 2, gy + GIVE_H + 2, COLOUR_GIVE_NOTE, true));
                break; // one line; a language whose note does not fit is cut, never spills out of the card
            }
            arrowCurrent = order.indexOf(reward);
            // Plain arrows just outside the card's left and right edges, centred on it.
            arrowY = y + h / 2 - font.lineHeight / 2;
            leftArrowX = x - ARROW_GAP - font.width(LEFT);
            rightArrowX = x + w + ARROW_GAP;
        }
        return y + h + GAP;
    }

    /** The card's text lines for {@code reward} from {@code top}, and the height they take. */
    private record CardLines(List<Text> lines, int bottom) {}

    private CardLines cardLines(Font font, CommunityRewards.Reward reward, LiveRewards state, long now,
                                int textX, int top, int textW) {
        int lh = font.lineHeight;
        int ty = top;
        List<Text> out = new ArrayList<>();
        out.add(new Text(Component.translatable(reward.nameKey()).getVisualOrderText(), textX, ty, COLOUR_WHITE, false));
        ty += lh + 2;
        for (FormattedCharSequence l : font.split(Component.translatable(reward.lineKey()), textW)) {
            out.add(new Text(l, textX, ty, style.text, false));
            ty += lh + 1;
        }
        if (CommunityRewards.active(state, reward, now)) {
            Component status = Component.translatable("gui.dungeontrain.rewards.status.on_by",
                    shortDate(state.find(reward.id()).activeUntilMs()),
                    CommunityRewards.keptOnBy(state, reward, now));
            for (FormattedCharSequence l : font.split(status, textW)) {
                out.add(new Text(l, textX, ty, COLOUR_ON, false));
                ty += lh + 1;
            }
        }
        return new CardLines(out, ty - top);
    }


    /**
     * A reward's purple Give button. Off: "Give $94" on the Contribute card ({@code shortForm}) or
     * "Give this month" on the shelf. On: "Give <month>", the month a gift would land in. Greyed
     * "Soon" while the relay takes no reward gifts yet.
     */
    static Button giveButton(CommunityRewards.Reward reward, boolean on, LiveRewards state, long now,
                             int x, int y, int w, int h, ContributePanel opener, boolean shortForm) {
        boolean open = CommunityRewards.givingOpen();
        Component label;
        if (!open) {
            label = Component.translatable("gui.dungeontrain.rewards.give.soon");
        } else if (on) {
            String month = CommunityRewards.nextFreeMonth(state, reward, now, ZoneId.systemDefault())
                    .getMonth().getDisplayName(shortForm ? TextStyle.SHORT : TextStyle.FULL, javaLocale());
            label = Component.translatable("gui.dungeontrain.rewards.give.month", month);
        } else if (shortForm) {
            label = Component.translatable("gui.dungeontrain.rewards.give.amount",
                    CurrencyFormat.amount(reward.costAud(), reward.amountShortKey()));
        } else {
            label = Component.translatable("gui.dungeontrain.rewards.give.this_month");
        }
        Button b = new ColorTintedButton(x, y, w, h, label, TINT_PURPLE[0], TINT_PURPLE[1], TINT_PURPLE[2],
                p -> opener.openLink(CommunityRewards.giveUrl(reward), reward.analyticsTarget()));
        b.active = open;
        return b;
    }

    private Button openEndedButton(int x, int y, int w, int h, boolean donate) {
        boolean cn = !donate && PaymentLinks.useChinaPayment();
        String key = donate ? "gui.dungeontrain.contribute.any_amount"
                : cn ? "gui.dungeontrain.death.narr.donate_cn" : "gui.dungeontrain.contribute.monthly";
        float[] t = donate ? TINT_GREEN : cn ? TINT_BLUE : TINT_ORANGE;
        String url = donate ? PaymentLinks.donateUrl() : cn ? PaymentLinks.chinaUrl() : OfficialLinks.patreon();
        String target = donate ? UiAnalytics.TARGET_DONATE : cn ? UiAnalytics.TARGET_DONATE_CN : UiAnalytics.TARGET_PATREON;
        Button b = new ColorTintedButton(x, y, w, h, Component.translatable(key), t[0], t[1], t[2],
                p -> openLink(url, target));
        b.setTooltip(Tooltip.create(Component.translatable(donate
                ? "gui.dungeontrain.support.donate_tooltip" : "gui.dungeontrain.support.recurring_tooltip")));
        return b;
    }

    /**
     * "Your own way: Any Amount · Monthly on Patreon. Amounts in Australian dollars." with the two
     * options as inline links; Simplified Chinese drops the first sentence (both providers are walled
     * there), and the Contribute page adds the affiliate sentence.
     */
    private Component footer(boolean tiers) {
        MutableComponent out = Component.empty();
        if (tiers && !PaymentLinks.hidesOpenEndedOptions()) {
            out.append(Component.translatable("gui.dungeontrain.contribute.own_way",
                    link("gui.dungeontrain.contribute.any_amount", LINK_DONATE),
                    link("gui.dungeontrain.contribute.monthly", LINK_PATREON))).append(" ");
        }
        // "Amounts in Australian dollars.", or the approximate-currency line while converted.
        out.append(CurrencyFormat.note());
        if (style.affiliate) {
            out.append(" ").append(Component.translatable("gui.dungeontrain.contribute.affiliate",
                    link("gui.dungeontrain.support.financial.affiliate_link", LINK_AFFILIATE)));
        }
        return out;
    }

    private static MutableComponent link(String key, String marker) {
        return Component.translatable(key).withStyle(s -> s.withColor(COLOUR_LINK).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, marker)));
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        render(g, font);
        if (cardW > 0) {
            boolean overLeft = overArrow(font, mouseX, mouseY, true);
            boolean overRight = overArrow(font, mouseX, mouseY, false);
            g.drawString(font, LEFT, leftArrowX, arrowY, overLeft ? COLOUR_ARROW_HOVER : COLOUR_ARROW, false);
            g.drawString(font, RIGHT, rightArrowX, arrowY, overRight ? COLOUR_ARROW_HOVER : COLOUR_ARROW, false);
        }
    }

    /** Whether the mouse is over an arrow (its glyph padded by {@link #ARROW_GAP} on every side). */
    private boolean overArrow(Font font, double mx, double my, boolean left) {
        int ax = left ? leftArrowX : rightArrowX;
        int aw = font.width(left ? LEFT : RIGHT);
        return mx >= ax - ARROW_GAP && mx <= ax + aw + ARROW_GAP
                && my >= arrowY - ARROW_GAP && my <= arrowY + font.lineHeight + ARROW_GAP;
    }

    /** One step through the rewards in {@link CommunityRewards#FEATURE_ORDER}, wrapping round. */
    private void step(int dir) {
        int n = CommunityRewards.FEATURE_ORDER.size();
        pick = ((arrowCurrent + dir) % n + n) % n;
        slideDir = dir;
        slideStartMs = System.currentTimeMillis();
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        relayout.run();
    }

    private void render(GuiGraphics g, Font font) {
        if (cardW > 0) {
            g.fill(cardX, cardY, cardX + cardW, cardY + cardH, COLOUR_CARD);
            g.renderOutline(cardX, cardY, cardW, cardH, COLOUR_CARD_EDGE);
        }
        if (featW > 0) g.renderOutline(featX - 1, featY - 1, featW + 2, featH + 2, COLOUR_FEATURED_EDGE);
        if (!cardTexts.isEmpty()) {
            // After an arrow press the new reward slides in from that side and settles; Give appears
            // once it has, so a click never lands on a button that is still moving.
            float t = slideDir == 0 ? 1f : Math.min(1f, (System.currentTimeMillis() - slideStartMs) / (float) SLIDE_MS);
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            int offset = Math.round(slideDir * (1f - eased) * cardW);
            if (t >= 1f) slideDir = 0;
            if (giveOnCard != null) giveOnCard.visible = offset == 0;
            g.enableScissor(cardX + 1, cardY + 1, cardX + cardW - 1, cardY + cardH - 1);
            for (Text tx : cardTexts) {
                int x = (tx.centred() ? tx.x() - font.width(tx.line()) / 2 : tx.x()) + offset;
                g.drawString(font, tx.line(), x, tx.y(), tx.colour(), false);
            }
            if (!cardIcon.isEmpty()) g.renderItem(cardIcon, cardIconX + offset, cardIconY);
            g.disableScissor();
        }
        for (Text t : texts) {
            int x = t.centred() ? t.x() - font.width(t.line()) / 2 : t.x();
            g.drawString(font, t.line(), x, t.y(), t.colour(), false);
        }
        int y = footerY;
        for (FormattedCharSequence l : footerLines) {
            g.drawString(font, l, footerX, y, style.text, false);
            y += font.lineHeight + 1;
        }
    }

    /** Follow a footer link under the mouse; true when one was hit. */
    public boolean mouseClicked(Font font, double mx, double my) {
        if (cardW > 0 && overArrow(font, mx, my, true)) { step(-1); return true; }
        if (cardW > 0 && overArrow(font, mx, my, false)) { step(1); return true; }
        if (footerLines.isEmpty() || mx < footerX || mx > footerX + footerW) return false;
        int row = (int) ((my - footerY) / (font.lineHeight + 1));
        if (my < footerY || row < 0 || row >= footerLines.size()) return false;
        net.minecraft.network.chat.Style s = font.getSplitter().componentStyleAtWidth(footerLines.get(row), (int) (mx - footerX));
        if (s == null || s.getClickEvent() == null) return false;
        switch (s.getClickEvent().getValue()) {
            case LINK_DONATE -> openLink(PaymentLinks.donateUrl(), UiAnalytics.TARGET_DONATE);
            case LINK_PATREON -> openLink(OfficialLinks.patreon(), UiAnalytics.TARGET_PATREON);
            case LINK_AFFILIATE -> openLink(OfficialLinks.affiliate(), UiAnalytics.TARGET_AFFILIATE);
            default -> { return false; }
        }
        return true;
    }

    private void openRewards() {
        UiAnalytics.click(surface, UiAnalytics.TARGET_REWARDS);
        Minecraft.getInstance().setScreen(new CommunityRewardsScreen(host));
    }

    /** Payment confirm for money targets, vanilla's for the rest; returns to the host either way. */
    void openLink(String url, String target) {
        if (url == null) return;
        if (target != null) UiAnalytics.click(surface, target);
        if (PaymentConfirmScreen.tryOpen(host, surface, target, url)) return;
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (target != null) UiAnalytics.confirm(surface, target, yes);
            if (yes) Util.getPlatform().openUri(URI.create(url));
            Minecraft.getInstance().setScreen(host);
        }, url, true));
    }

    static String shortDate(long ms) {
        return java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", javaLocale()));
    }

    static String locale() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The game language as a Java locale, for month names. */
    static Locale javaLocale() {
        String mc = locale();
        if (mc == null || mc.isEmpty()) return Locale.ENGLISH;
        String[] p = mc.split("_");
        return p.length > 1 ? Locale.of(p[0], p[1].toUpperCase(Locale.ROOT)) : Locale.of(p[0]);
    }
}
