package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.support.currency.CurrencyFormat;
import games.brennan.dungeontrain.client.support.currency.CurrencyPicker;
import games.brennan.dungeontrain.client.DonationSummaryCache;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.live.life.LiveRewards;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Community Rewards: the whole shelf, opened by the Contribute panel's See All Rewards, by
 * {@code /dungeontrain-rewards} (the thank-you chat line's link) and later by the Live Eye death
 * pages. Five cards, three per row (two on a narrow window), each: group, name, "$94 AUD keeps it on a
 * month", status, its one line and a Give button; the reward Contribute features is outlined so the
 * player lands where they were. Below, "This month's thanks". Back returns to the parent.
 *
 * <p>The cards scroll inside a clipped viewport like Ways to Help's sections; Back is pinned.</p>
 */
public final class CommunityRewardsScreen extends Screen implements games.brennan.dungeontrain.client.links.RefreshOnLinks {

    private static final int MAX_COL_W = 720;
    private static final int SIDE_MARGIN = 40;
    /** Below this column width the cards go two per row; at or above it, three. */
    private static final int THREE_ACROSS_MIN = 330;
    private static final int GAP = 6;
    private static final int PAD = 6;
    private static final int PANEL_PAD = 10;
    private static final int TOP = 16;
    private static final int BOTTOM_ROW_H = 28;
    private static final int GIVE_H = 20;
    private static final int SCROLL_STEP = 12;

    private static final int COLOUR_TITLE = 0xFFFFFFFF;
    private static final int COLOUR_SUB = 0xFF9A9A9A;
    private static final int COLOUR_CARD = 0x16FFFFFF;
    private static final int COLOUR_CARD_EDGE = 0xFF3A3A3A;
    private static final int COLOUR_FEATURED_EDGE = 0xFF8B6BD0;
    private static final int COLOUR_GROUP = 0xFF8A7C60;
    private static final int COLOUR_NAME = 0xFFFFFFFF;
    private static final int COLOUR_AMOUNT = 0xFFF0B35A;
    private static final int COLOUR_LINE = 0xFFBDBDBD;
    private static final int COLOUR_HEADER = 0xFFFFD05A;

    private final Screen parent;
    /** Only for Give's link handling (confirm + analytics); never laid out. */
    private final ContributePanel links;
    private final CurrencyPicker currency = new CurrencyPicker(UiAnalytics.SURFACE_REWARDS_PAGE, this::rebuildWidgets);
    private final long openedAtMs = System.currentTimeMillis();
    private boolean timeReported;

    private record Text(FormattedCharSequence line, int x, int canvasY, int colour) {}
    private record Card(int x, int canvasY, int w, int h, int edge) {}
    private record Placed(Button button, int canvasY) {}
    private record Icon(CommunityRewards.Reward reward, int x, int canvasY) {}
    private record PersonalIcon(SupportTier tier, int x, int canvasY) {}
    private final List<PersonalIcon> personalIcons = new ArrayList<>();
    private static final int COLOUR_PERSONAL_TITLE = 0x8FE08A;
    private static final int COLOUR_PERSONAL_FEATURED_EDGE = 0xFF8FE08A;
    private final List<Icon> icons = new ArrayList<>();
    private static final int ICON = 16;
    private static final int ICON_GAP = 4;

    /** A background card behind one section, in canvas coordinates. */
    private record Panel(int top, int bottom, int colour) {}
    /** The community card is purple, the personal card green. */
    private static final int COLOUR_PANEL_COMMUNITY = 0xEA0C0714;
    private static final int COLOUR_PANEL_PERSONAL = 0xEA050E08;
    private final List<Panel> panels = new ArrayList<>();
    private static final int PANEL_GAP = 6;

    /** One icon in "What each reward does": where it is and which entry it selects. */
    private record ExplainIcon(int index, int count, int x, int canvasY) {}
    private final List<ExplainIcon> explainIcons = new ArrayList<>();
    /** The entry whose explanation shows; the first (your name aboard) until one is clicked. */
    private int explainSelected = 0;
    private static final int EXPLAIN_CELL = 20;
    private static final int COLOUR_SELECTED_EDGE = 0xFFFFFFFF;
    private static final int COLOUR_HOVER_EDGE = 0xFF6A6A6A;

    private final List<Text> texts = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Placed> buttons = new ArrayList<>();
    private int colX, colW, viewportTop, viewportBottom, scrollY, maxScroll;

    public CommunityRewardsScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.dungeontrain.rewards.title"));
        this.parent = parent;
        this.links = new ContributePanel(this, UiAnalytics.SURFACE_REWARDS_PAGE, ContributePanel.Style.PAGE, () -> {});
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_REWARDS_PAGE);
        OfficialLinks.ensureFetched();
        refreshSummary(this);
    }

    /**
     * Re-read the donation summary (and with it {@code rewards[]}) and lay {@code screen} out again
     * if it is still showing. The player's name goes only with network consent, as everywhere.
     */
    static void refreshSummary(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        String name = mc.getUser() != null ? mc.getUser().getName() : null;
        java.util.UUID id = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        DonationSummaryClient.fetch(name, id, summary -> Minecraft.getInstance().execute(() -> {
            DonationSummaryCache.set(summary);
            if (Minecraft.getInstance().screen == screen) screen.init(Minecraft.getInstance(), screen.width, screen.height);
        }));
    }

    @Override
    protected void init() {
        texts.clear();
        icons.clear();
        personalIcons.clear();
        panels.clear();
        explainIcons.clear();
        cards.clear();
        buttons.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;
        long now = System.currentTimeMillis();
        LiveRewards state = LiveRewards.latest();
        CommunityRewards.Reward featured = CommunityRewards.featured(state, now);

        int y = 0;
        texts.add(centred(this.title, y, COLOUR_TITLE));
        y += lh + 4;
        for (FormattedCharSequence l : this.font.split(Component.translatable("gui.dungeontrain.rewards.subtitle"), colW)) {
            texts.add(new Text(l, colX + colW / 2 - this.font.width(l) / 2, y, COLOUR_SUB));
            y += lh + 1;
        }
        y += 8;

        int perRow = colW >= THREE_ACROSS_MIN ? 3 : 2;
        int cardW = (colW - GAP * (perRow - 1)) / perRow;
        CommunityRewards.Reward[] all = CommunityRewards.Reward.values();
        for (int start = 0; start < all.length; start += perRow) {
            int end = Math.min(all.length, start + perRow);
            int rowH = 0;
            for (int i = start; i < end; i++) {
                rowH = Math.max(rowH, card(all[i], state, now, colX + (i - start) * (cardW + GAP), y, cardW));
            }
            // Equal heights in a row, Give buttons on one baseline.
            for (int i = start; i < end; i++) {
                cards.add(new Card(colX + (i - start) * (cardW + GAP), y, cardW, rowH,
                        all[i] == featured ? COLOUR_FEATURED_EDGE : COLOUR_CARD_EDGE));
            }
            int giveY = y + rowH - PAD - GIVE_H;
            for (int i = start; i < end; i++) {
                CommunityRewards.Reward r = all[i];
                int cx = colX + (i - start) * (cardW + GAP);
                Button b = ContributePanel.giveButton(r, CommunityRewards.active(state, r, now), state, now,
                        cx + PAD, giveY, cardW - PAD * 2, GIVE_H, links, false);
                buttons.add(new Placed(b, giveY));
                addWidget(b);
            }
            y += rowH + GAP;
        }

        y += 4;
        texts.add(new Text(Component.translatable("gui.dungeontrain.rewards.thanks.header").getVisualOrderText(),
                colX, y, COLOUR_HEADER));
        y += lh + 3;
        for (FormattedCharSequence l : this.font.split(ledger(state, now), colW)) {
            texts.add(new Text(l, colX, y, COLOUR_LINE));
            y += lh + 1;
        }

        // Converted amounts above: say they are approximate and that the gift is made in AUD.
        if (CurrencyFormat.converted()) {
            y += 4;
            for (FormattedCharSequence l : this.font.split(CurrencyFormat.note(), colW)) {
                texts.add(new Text(l, colX, y, COLOUR_SUB));
                y += lh + 1;
            }
        }

        // Three cards on the page: community rewards, personal rewards, and what each reward does.
        panels.add(new Panel(-PANEL_PAD, y + PANEL_PAD, COLOUR_PANEL_COMMUNITY));
        y += PANEL_PAD + PANEL_GAP + PANEL_PAD;
        int personalTop = y;
        y = personalSection(y, perRow, cardW);
        y = explainer(y + 4);
        panels.add(new Panel(personalTop - PANEL_PAD, y + PANEL_PAD, COLOUR_PANEL_PERSONAL));
        y += PANEL_PAD;

        int contentH = y;
        int rowY = this.height - BOTTOM_ROW_H;
        viewportTop = TOP;
        viewportBottom = Math.max(viewportTop, rowY - PANEL_PAD);
        maxScroll = Math.max(0, contentH - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        // Top right of the column, level with the title; drawn by render() above the scrolling cards.
        currency.layout(this.font, colX + colW, viewportTop - 3, this.height, this::addWidget);
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - 100, rowY, 200, 20).build());
    }

    /** "What each reward does": the three personal thank-yous. */
    private static final String[] PERSONAL_EXPLAIN = {"name_aboard", "priority_books", "copy"};

    private static Component explainName(int i) {
        return Component.translatable("gui.dungeontrain.rewards.explain." + PERSONAL_EXPLAIN[i] + ".name");
    }

    private static Component explainText(int i) {
        return Component.translatable("gui.dungeontrain.rewards.explain." + PERSONAL_EXPLAIN[i]);
    }

    private void renderExplainIcon(GuiGraphics g, int i, int count, int x, int y) {
        net.minecraft.world.item.Item item = switch (PERSONAL_EXPLAIN[i]) {
            case "name_aboard" -> net.minecraft.world.item.Items.NAME_TAG;
            case "priority_books" -> net.minecraft.world.item.Items.WRITABLE_BOOK;
            default -> net.minecraft.world.item.Items.PAPER;
        };
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, count);
        g.renderItem(stack, x, y);
        g.renderItemDecorations(this.font, stack, x, y);
    }

    /**
     * "What each reward does", inside the Personal Rewards card under the tiers: the name and a short
     * explanation of whichever thank-you icon was clicked in a tier card.
     */
    private int explainer(int y) {
        int lh = this.font.lineHeight;
        for (FormattedCharSequence l : this.font.split(Component.translatable("gui.dungeontrain.rewards.explain.title"), colW)) {
            texts.add(new Text(l, colX, y, COLOUR_HEADER));
            y += lh + 1;
        }
        y += 4;
        texts.add(new Text(explainName(explainSelected).copy().withStyle(st -> st.withBold(true)).getVisualOrderText(),
                colX, y, COLOUR_NAME));
        y += lh + 3;
        for (FormattedCharSequence l : this.font.split(explainText(explainSelected), colW)) {
            texts.add(new Text(l, colX, y, COLOUR_LINE));
            y += lh + 1;
        }
        return y;
    }

    /**
     * Personal Rewards: the three gift tiers and what each gives the giver back. Only thank-yous that
     * exist today: your name aboard (the boards and Credits), one Priority Book per $10, and the book
     * screen's Copy button (an option, for anyone who has given $30 or more). The middle tier is
     * outlined green as on Contribute; Give goes through the same payment confirm.
     */
    private int personalSection(int y, int perRow, int cardW) {
        int lh = this.font.lineHeight;
        Component title = Component.translatable("gui.dungeontrain.rewards.personal.title")
                .withStyle(st -> st.withBold(true).withColor(COLOUR_PERSONAL_TITLE));
        Component heading = Component.empty().append(title).append(" · ")
                .append(Component.translatable("gui.dungeontrain.rewards.personal.label"));
        for (FormattedCharSequence l : this.font.split(heading, colW)) {
            texts.add(new Text(l, colX, y, COLOUR_GROUP));
            y += lh + 1;
        }
        y += 6;

        SupportTier[] tiers = SupportTier.values();
        for (int start = 0; start < tiers.length; start += perRow) {
            int end = Math.min(tiers.length, start + perRow);
            int rowH = 0;
            for (int i = start; i < end; i++) {
                rowH = Math.max(rowH, personalCard(tiers[i], colX + (i - start) * (cardW + GAP), y, cardW));
            }
            int giveY = y + rowH - PAD - GIVE_H;
            for (int i = start; i < end; i++) {
                SupportTier tier = tiers[i];
                int cx = colX + (i - start) * (cardW + GAP);
                cards.add(new Card(cx, y, cardW, rowH,
                        i == tiers.length / 2 ? COLOUR_PERSONAL_FEATURED_EDGE : COLOUR_CARD_EDGE));
                Button b = new games.brennan.dungeontrain.client.menu.ColorTintedButton(cx + PAD, giveY, cardW - PAD * 2, GIVE_H,
                        Component.translatable("gui.dungeontrain.rewards.give.amount",
                                CurrencyFormat.amount(tier.audAmount(), tier.shortKey())),
                        ContributePanel.TINT_GREEN[0], ContributePanel.TINT_GREEN[1], ContributePanel.TINT_GREEN[2],
                        p -> links.openLink(PaymentLinks.tierUrl(tier), tier.analyticsTarget()));
                b.active = PaymentLinks.tiersAvailable();
                buttons.add(new Placed(b, giveY));
                addWidget(b);
            }
            y += rowH + GAP;
        }
        return y;
    }

    /** One tier's card: icon and name, its amount, then its thank-yous. */
    private int personalCard(SupportTier tier, int x, int y, int w) {
        int lh = this.font.lineHeight;
        int tx = x + PAD;
        int tw = w - PAD * 2;
        int ty = y + PAD + 3;
        int iconTop = ty - 4;
        personalIcons.add(new PersonalIcon(tier, tx, iconTop));
        ty = wrap(Component.translatable(tier.nameKey()), tx + ICON + ICON_GAP, ty, tw - ICON - ICON_GAP, COLOUR_NAME);
        ty = Math.max(ty, iconTop + ICON + 1);
        // The amount is on the Give button; the yellow line sums up what the gift brings.
        ty = wrap(Component.translatable("gui.dungeontrain.rewards.personal.summary." + tier.name().toLowerCase(java.util.Locale.ROOT)),
                tx, ty, tw, COLOUR_AMOUNT);
        ty += 2;
        // The tier's thank-yous as inventory slots: name tag, Priority Books (stack = how many), copy.
        // Clicking one shows its explanation under the tiers.
        int slot = 0;
        for (int i = 0; i < PERSONAL_EXPLAIN.length; i++) {
            // Copy books is for supporters of $30 or more, so the $10 tier does not show it.
            if ("copy".equals(PERSONAL_EXPLAIN[i]) && tier.audAmount() < SupporterStatus.MIN_TOTAL_AUD) continue;
            int count = "priority_books".equals(PERSONAL_EXPLAIN[i]) ? tier.quantity() : 1;
            explainIcons.add(new ExplainIcon(i, count, tx + slot * EXPLAIN_CELL, ty));
            slot++;
        }
        ty += EXPLAIN_CELL;
        return ty - y + 4 + GIVE_H + PAD;
    }

    /** Lay out one card's text; returns its height including room for the Give button. */
    private int card(CommunityRewards.Reward r, LiveRewards state, long now, int x, int y, int w) {
        int lh = this.font.lineHeight;
        int tx = x + PAD;
        int tw = w - PAD * 2;
        int ty = y + PAD;
        texts.add(new Text(Component.translatable(r.group().labelKey()).getVisualOrderText(), tx, ty, COLOUR_GROUP));
        ty += lh + 1;
        // Icon left of the name; the name wraps beside it. 3 px of air so the icon clears the label.
        ty += 3;
        int iconTop = ty - 4;
        icons.add(new Icon(r, tx, iconTop));
        ty = wrap(Component.translatable(r.nameKey()), tx + ICON + ICON_GAP, ty, tw - ICON - ICON_GAP, COLOUR_NAME);
        ty = Math.max(ty, iconTop + ICON + 1);
        // The amount is on the Give button; the yellow line only says who it is for.
        ty = wrap(Component.translatable("gui.dungeontrain.contribute.keeps_on"), tx, ty, tw, COLOUR_AMOUNT);
        ty += 2;
        ty += 2;
        ty = wrap(Component.translatable(r.lineKey()), tx, ty, tw, COLOUR_LINE);
        return ty - y + 4 + GIVE_H + PAD;
    }

    private int wrap(Component c, int x, int y, int w, int colour) {
        for (FormattedCharSequence l : this.font.split(c, w)) {
            texts.add(new Text(l, x, y, colour));
            y += this.font.lineHeight + 1;
        }
        return y;
    }

    private Text centred(Component c, int y, int colour) {
        FormattedCharSequence l = c.getVisualOrderText();
        return new Text(l, colX + colW / 2 - this.font.width(l) / 2, y, colour);
    }

    /** "Ash HD Streams · Moth Replays Kept, Quicker Streams (Dec)", or the empty line. */
    private static Component ledger(LiveRewards state, long now) {
        Map<String, List<Component>> byGiver = new LinkedHashMap<>();
        for (CommunityRewards.Reward r : CommunityRewards.Reward.values()) {
            LiveRewards.Reward live = state.find(r.id());
            if (live == null || live.activeUntilMs() <= now) continue;
            if (!live.by().isEmpty()) {
                byGiver.computeIfAbsent(live.by(), k -> new ArrayList<>()).add(Component.translatable(r.nameKey()));
            }
            long from = live.activeUntilMs();
            for (String who : live.queuedBy()) {
                if (!who.isEmpty()) {
                    byGiver.computeIfAbsent(who, k -> new ArrayList<>()).add(Component.translatable(
                            "gui.dungeontrain.rewards.thanks.later", Component.translatable(r.nameKey()),
                            ContributePanel.shortDate(from)));
                }
                from += CommunityRewards.MONTH_MS;
            }
        }
        if (byGiver.isEmpty()) return Component.translatable("gui.dungeontrain.rewards.thanks.none");
        MutableComponent out = Component.empty();
        boolean first = true;
        for (Map.Entry<String, List<Component>> e : byGiver.entrySet()) {
            if (!first) out.append(" · ");
            first = false;
            out.append(Component.literal(e.getKey()).withStyle(s -> s.withColor(0xFFFFFF))).append(" ");
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) out.append(", ");
                out.append(e.getValue().get(i));
            }
        }
        return out;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int off = viewportTop - scrollY;
        for (Placed p : buttons) {
            int by = p.canvasY() + off;
            p.button().setY(by);
            p.button().visible = by >= viewportTop && by + p.button().getHeight() <= viewportBottom;
        }
        super.render(g, mouseX, mouseY, partialTick);
        g.enableScissor(colX - PANEL_PAD, viewportTop - PANEL_PAD, colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD);
        for (Panel p : panels) {
            g.fill(colX - PANEL_PAD, p.top() + off, colX + colW + PANEL_PAD, p.bottom() + off, p.colour());
        }
        for (Card c : cards) {
            int cy = c.canvasY() + off;
            g.fill(c.x(), cy, c.x() + c.w(), cy + c.h(), COLOUR_CARD);
            g.renderOutline(c.x(), cy, c.w(), c.h(), c.edge());
        }
        for (Text t : texts) g.drawString(this.font, t.line(), t.x(), t.canvasY() + off, t.colour(), false);
        for (ExplainIcon e : explainIcons) {
            int ex = e.x(), ey = e.canvasY() + off;
            boolean hover = mouseX >= ex && mouseX < ex + EXPLAIN_CELL - 2 && mouseY >= ey && mouseY < ey + EXPLAIN_CELL - 2;
            if (e.index() == explainSelected) g.renderOutline(ex, ey, EXPLAIN_CELL - 2, EXPLAIN_CELL - 2, COLOUR_SELECTED_EDGE);
            else if (hover) g.renderOutline(ex, ey, EXPLAIN_CELL - 2, EXPLAIN_CELL - 2, COLOUR_HOVER_EDGE);
            renderExplainIcon(g, e.index(), e.count(), ex + 1, ey + 1);
        }
        for (Icon i : icons) RewardIcons.render(g, i.reward(), i.x(), i.canvasY() + off);
        for (PersonalIcon i : personalIcons) g.renderItem(RewardIcons.of(i.tier()), i.x(), i.canvasY() + off);
        for (Placed p : buttons) if (p.button().visible) p.button().render(g, mouseX, mouseY, partialTick);
        g.disableScissor();
        // The picker sits over the top of the first card: show it only while the page is at the top.
        if (currency.button() != null) {
            currency.button().visible = scrollY == 0;
            currency.button().render(g, mouseX, mouseY, partialTick);
        }
        currency.render(g, this.font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (currency.mouseClicked(mx, my, button)) return true;
        if (button == 0 && my >= viewportTop && my <= viewportBottom) {
            int off = viewportTop - scrollY;
            for (ExplainIcon e : explainIcons) {
                int ey = e.canvasY() + off;
                if (mx >= e.x() && mx < e.x() + EXPLAIN_CELL - 2 && my >= ey && my < ey + EXPLAIN_CELL - 2) {
                    explainSelected = e.index();
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                            .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return currency.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Dev preview: scroll to the bottom (the Personal Rewards section). */
    void scrollToEnd() {
        scrollY = maxScroll;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (currency.mouseScrolled(mx, my, dy)) return true;
        if (maxScroll > 0) {
            scrollY = Mth.clamp(scrollY - (int) Math.round(dy * SCROLL_STEP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public void onClose() {
        if (!timeReported) {
            timeReported = true;
            UiAnalytics.pageTime(UiAnalytics.SURFACE_REWARDS_PAGE, System.currentTimeMillis() - openedAtMs);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
