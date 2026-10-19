package games.brennan.dungeontrain.client.support;

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
public final class CommunityRewardsScreen extends Screen {

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

    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_TITLE = 0xFFFFFFFF;
    private static final int COLOUR_SUB = 0xFF9A9A9A;
    private static final int COLOUR_CARD = 0x0AFFFFFF;
    private static final int COLOUR_CARD_EDGE = 0xFF3A3A3A;
    private static final int COLOUR_FEATURED_EDGE = 0xFF8B6BD0;
    private static final int COLOUR_GROUP = 0xFF8A7C60;
    private static final int COLOUR_NAME = 0xFFFFFFFF;
    private static final int COLOUR_AMOUNT = 0xFFF0B35A;
    private static final int COLOUR_ON = 0xFF8FE08A;
    private static final int COLOUR_OFF = 0xFF9A9A9A;
    private static final int COLOUR_LINE = 0xFFBDBDBD;
    private static final int COLOUR_HEADER = 0xFFFFD05A;

    private final Screen parent;
    /** Only for Give's link handling (confirm + analytics); never laid out. */
    private final ContributePanel links;
    private final long openedAtMs = System.currentTimeMillis();
    private boolean timeReported;

    private record Text(FormattedCharSequence line, int x, int canvasY, int colour) {}
    private record Card(int x, int canvasY, int w, int h, boolean featured) {}
    private record Placed(Button button, int canvasY) {}

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
                cards.add(new Card(colX + (i - start) * (cardW + GAP), y, cardW, rowH, all[i] == featured));
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

        int contentH = y;
        int rowY = this.height - BOTTOM_ROW_H;
        viewportTop = TOP;
        viewportBottom = Math.max(viewportTop, rowY - PANEL_PAD);
        maxScroll = Math.max(0, contentH - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - 100, rowY, 200, 20).build());
    }

    /** Lay out one card's text; returns its height including room for the Give button. */
    private int card(CommunityRewards.Reward r, LiveRewards state, long now, int x, int y, int w) {
        int lh = this.font.lineHeight;
        int tx = x + PAD;
        int tw = w - PAD * 2;
        int ty = y + PAD;
        texts.add(new Text(Component.translatable(r.group().labelKey()).getVisualOrderText(), tx, ty, COLOUR_GROUP));
        ty += lh + 1;
        ty = wrap(Component.translatable(r.nameKey()), tx, ty, tw, COLOUR_NAME);
        ty = wrap(Component.translatable("gui.dungeontrain.rewards.amount_line", Component.translatable(r.amountKey())),
                tx, ty, tw, COLOUR_AMOUNT);
        ty += 2;
        if (CommunityRewards.active(state, r, now)) {
            LiveRewards.Reward live = state.find(r.id());
            ty = wrap(Component.translatable("gui.dungeontrain.rewards.status.on_until",
                    ContributePanel.shortDate(live.activeUntilMs())), tx, ty, tw, COLOUR_ON);
            ty = wrap(Component.translatable("gui.dungeontrain.rewards.status.kept_by", live.by()), tx, ty, tw, COLOUR_ON);
            long from = live.activeUntilMs();
            for (String who : live.queuedBy()) {
                // Labelled by the day it starts: "14 Nov: Moth" (a month name alone reads as overlapping).
                ty = wrap(Component.translatable("gui.dungeontrain.rewards.status.queued",
                        ContributePanel.shortDate(from), who), tx, ty, tw, COLOUR_ON);
                from += CommunityRewards.MONTH_MS;
            }
        } else {
            ty = wrap(Component.translatable("gui.dungeontrain.rewards.status.off"), tx, ty, tw, COLOUR_OFF);
        }
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
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD, colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
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
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);
        for (Card c : cards) {
            int cy = c.canvasY() + off;
            g.fill(c.x(), cy, c.x() + c.w(), cy + c.h(), COLOUR_CARD);
            g.renderOutline(c.x(), cy, c.w(), c.h(), c.featured() ? COLOUR_FEATURED_EDGE : COLOUR_CARD_EDGE);
        }
        for (Text t : texts) g.drawString(this.font, t.line(), t.x(), t.canvasY() + off, t.colour(), false);
        for (Placed p : buttons) if (p.button().visible) p.button().render(g, mouseX, mouseY, partialTick);
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
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
