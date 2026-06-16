package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.SurveyClientState;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.discordpresence.network.SurveySubmitPayload;
import games.brennan.dungeontrain.net.DeathNarrative;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The paginated "the Dungeon Train asks" death screen — a guided, single-button
 * narrative the train tells about the passenger. Replaces the vanilla
 * {@code DeathScreen} on singleplayer DT worlds (see
 * {@link DeathScreenLayoutHandler}).
 *
 * <p>Pages, in order: <b>the fall</b> (this-life headline stats) → <b>the
 * deeds</b> (this-life combat + carried loadout) → <b>all your lives</b>
 * (cross-world lifetime totals) → one page per unanswered feedback-survey
 * question (driven by the bundled Discord Presence survey, submitted through
 * its public API) → <b>the platform</b> (Board anew / Leave). A single centered
 * <i>Continue</i> advances; a bare back-arrow returns; <i>reboard</i> and
 * <i>leave the line</i> sit in the top corner throughout.</p>
 *
 * <p>All per-run + lifetime numbers and the rolled narrative come from the
 * cached {@link DeathStatsPacket} ({@link DeathStatsCache}); the screen reads
 * it live each frame so a one-tick-late packet simply fills in. The survey
 * page set is rebuilt if the cached questions arrive after the screen opens.</p>
 */
public final class NarrativeDeathScreen extends Screen {

    private enum Kind { FALL, DEEDS, GEAR, LIVES, SURVEY, PLATFORM }

    private record Page(Kind kind, SurveyQuestionPayload.Entry survey) {
        static Page of(Kind k) { return new Page(k, null); }
        static Page survey(SurveyQuestionPayload.Entry e) { return new Page(Kind.SURVEY, e); }
    }

    private record Rect(int x, int y, int w, int h) {
        boolean has(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    // ---- Palette (ARGB) ----
    private static final int OVERLAY        = 0xE6090A0D;
    private static final int TILE_BG        = 0x14FFFFFF;
    private static final int TILE_BORDER    = 0x33D6C496;
    private static final int VALUE          = 0xFFE6D6B0;
    private static final int LABEL          = 0xFF9A8F74;
    private static final int QUESTION       = 0xFFE0B56A;
    private static final int NARR           = 0xFFC7BDA7;
    private static final int KICKER         = 0xFF8A7C60;
    private static final int SUBLINE        = 0xFF948A70;
    private static final int RED            = 0xFFFF5555;
    private static final int RAIL           = 0xFF43454E;
    private static final int INF            = 0xFF5A5C66;
    private static final int SLOT_BG        = 0xFF211E1A;
    private static final int SLOT_LIGHT     = 0xFF3A352D;
    private static final int SLOT_DARK      = 0xFF100E0B;
    private static final int CHIP_RB_BORDER = 0xFF34503A;
    private static final int CHIP_RB_TEXT   = 0xFF7FAE84;
    private static final int CHIP_LV_BORDER = 0xFF2A2D33;
    private static final int CHIP_LV_TEXT   = 0xFF8A909A;
    private static final int BTN_BG         = 0xFF585B5E;
    private static final int BTN_LIGHT      = 0xFF76797D;
    private static final int BTN_DARK       = 0xFF1E1F21;
    private static final int BTN_TEXT       = 0xFFEAEAEA;
    private static final int BTN_PRI_BG     = 0xFF3C6B41;
    private static final int BTN_PRI_LIGHT  = 0xFF5C9162;
    private static final int DOT_OFF        = 0xFF2A2D33;
    private static final int DOT_ON         = 0xFFC9923F;
    private static final int SCORE_BG       = 0xFF1A1813;
    private static final int SCORE_BORDER   = 0xFF4A443C;
    private static final int SCORE_TEXT     = 0xFFCDBB95;

    private static final int MAX_CONTENT_W = 360;
    private static final int SLOT = 18;

    private final Map<String, Integer> scores = new HashMap<>();
    private final Map<String, String> comments = new HashMap<>();
    private final Set<String> submitted = new HashSet<>();

    private List<Page> pages = List.of();
    private int currentPage = 0;
    private int lastSurveyCount = -1;
    private EditBox commentBox;

    // Clickable regions, recomputed each render() and read by mouseClicked().
    private Rect reboardRect, leaveRect, continueRect, backRect, boardAnewRect, platformLeaveRect;
    private final List<Rect> scoreRects = new ArrayList<>();

    public NarrativeDeathScreen() {
        super(Component.translatable("gui.dungeontrain.death.narr.title"));
    }

    private List<Page> buildPages() {
        List<Page> list = new ArrayList<>();
        list.add(Page.of(Kind.FALL));
        list.add(Page.of(Kind.DEEDS));
        list.add(Page.of(Kind.GEAR));
        list.add(Page.of(Kind.LIVES));
        for (SurveyQuestionPayload.Entry e : SurveyClientState.questions()) {
            list.add(Page.survey(e));
        }
        list.add(Page.of(Kind.PLATFORM));
        return list;
    }

    @Override
    protected void init() {
        pages = buildPages();
        if (currentPage >= pages.size()) currentPage = pages.size() - 1;
        if (currentPage < 0) currentPage = 0;
        lastSurveyCount = SurveyClientState.questions().size();
        commentBox = null;

        Page p = pages.get(currentPage);
        if (p.kind() == Kind.SURVEY && p.survey() != null && p.survey().allowComment()) {
            commentBox = new EditBox(this.font, 0, 0, 100, 16,
                    Component.translatable("gui.dungeontrain.death.narr.comment"));
            commentBox.setMaxLength(256);
            String qid = p.survey().id();
            commentBox.setValue(comments.getOrDefault(qid, ""));
            commentBox.setResponder(s -> comments.put(qid, s));
            commentBox.setHint(Component.translatable("gui.dungeontrain.death.narr.comment"));
            addRenderableWidget(commentBox);
        }
    }

    @Override
    public void tick() {
        // The death packet + survey questions arrive a tick or two after the
        // screen opens. If the survey set changed, rebuild so the pages match.
        if (SurveyClientState.questions().size() != lastSurveyCount) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, OVERLAY);

        DeathStatsPacket stats = DeathStatsCache.get();
        DeathNarrative narr = stats != null ? stats.narrative() : DeathNarrative.EMPTY;
        Page page = pages.isEmpty() ? Page.of(Kind.FALL) : pages.get(currentPage);

        int contentW = Math.min(MAX_CONTENT_W, this.width - 40);
        int cx = this.width / 2;
        int left = cx - contentW / 2;

        drawTopBar(g, mouseX, mouseY);

        int y = 40;
        switch (page.kind()) {
            case FALL -> y = drawFall(g, stats, narr, left, contentW, cx, y, mouseX, mouseY);
            case DEEDS -> y = drawDeeds(g, stats, narr, left, contentW, cx, y, mouseX, mouseY);
            case GEAR -> y = drawGear(g, stats, narr, left, contentW, cx, y, mouseX, mouseY);
            case LIVES -> y = drawLives(g, stats, narr, left, contentW, cx, y);
            case SURVEY -> y = drawSurvey(g, page.survey(), left, contentW, cx, y);
            case PLATFORM -> y = drawPlatform(g, narr, left, contentW, cx, y);
        }

        drawFooter(g, page, mouseX, mouseY);

        // Widgets (the comment EditBox) render on top of the content.
        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }

        // Loadout hover tooltip last, above everything.
        if (page.kind() == Kind.GEAR && stats != null) {
            drawLoadoutTooltip(g, stats, left, contentW, mouseX, mouseY);
        }
    }

    // ---- Page renderers. Each returns the y below its content. ----

    private int drawFall(GuiGraphics g, DeathStatsPacket s, DeathNarrative n,
                         int left, int w, int cx, int y, int mouseX, int mouseY) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_fall");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawQuestion(g, n.fallQuestion(), cx, w, y);
        y = drawNarration(g, n.fallNarration(), cx, w, y);
        y += 6;
        drawSecLabel(g, cx, y, "gui.dungeontrain.death.narr.this_life");
        y += 13;
        if (s != null) {
            int third = w / 3;
            drawCell(g, left + third / 2, y, Integer.toString(s.cartsTravelled()), "gui.dungeontrain.death.narr.lbl_carriage");
            drawCell(g, left + third + third / 2, y, fmtDist(s.distanceBlocks()), "gui.dungeontrain.death.narr.lbl_distance");
            drawCell(g, left + 2 * third + third / 2, y, fmtTime(s.runTicks()), "gui.dungeontrain.death.narr.lbl_time");
        }
        return y + 34;
    }

    private int drawDeeds(GuiGraphics g, DeathStatsPacket s, DeathNarrative n,
                          int left, int w, int cx, int y, int mouseX, int mouseY) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_deeds");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawQuestion(g, n.deedsQuestion(), cx, w, y);
        y = drawNarration(g, n.deedsNarration(), cx, w, y);
        y += 6;
        drawSecLabel(g, cx, y, "gui.dungeontrain.death.narr.this_life");
        y += 13;
        if (s != null) {
            int third = w / 3;
            drawCell(g, left + third / 2, y, Integer.toString(s.mobKills()), "gui.dungeontrain.death.narr.lbl_mobs");
            drawCell(g, left + third + third / 2, y, Integer.toString(s.playersEncountered()), "gui.dungeontrain.death.narr.lbl_met");
            drawCell(g, left + 2 * third + third / 2, y, Integer.toString(s.playersKilled()), "gui.dungeontrain.death.narr.lbl_slain");
            int y2 = y + 30;
            drawCell(g, left + third / 2, y2, Integer.toString(s.playersBefriended()), "gui.dungeontrain.death.narr.lbl_befriended");
            drawCell(g, left + third + third / 2, y2, fmtDmg(s.damageDealt()), "gui.dungeontrain.death.narr.lbl_dealt");
            drawCell(g, left + 2 * third + third / 2, y2, fmtDmg(s.damageTaken()), "gui.dungeontrain.death.narr.lbl_taken");
            y = y2 + 30;
        }
        return y;
    }

    private int drawGear(GuiGraphics g, DeathStatsPacket s, DeathNarrative n,
                         int left, int w, int cx, int y, int mouseX, int mouseY) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_gear");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawQuestion(g, n.gearQuestion(), cx, w, y);
        y = drawNarration(g, n.gearNarration(), cx, w, y);
        y += 8;
        if (s != null) {
            drawSecLabel(g, cx, y, "gui.dungeontrain.death.narr.carried");
            y += 14;
            this.loadoutY = y;
            drawLoadout(g, s, left, w, y, mouseX, mouseY);
            y += SLOT + 8;
        }
        return y;
    }

    private int drawLives(GuiGraphics g, DeathStatsPacket s, DeathNarrative n,
                          int left, int w, int cx, int y) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_lives");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawQuestion(g, n.livesQuestion(), cx, w, y);
        if (!n.livesSubline().isEmpty()) {
            y = drawCentered(g, Component.literal(n.livesSubline()), cx, w, y, SUBLINE);
            y += 2;
        }
        y = drawNarration(g, n.livesNarration(), cx, w, y);
        y += 6;
        drawSecLabel(g, cx, y, "gui.dungeontrain.death.narr.all_lives");
        y += 13;
        if (s != null) {
            int third = w / 3;
            drawCell(g, left + third / 2, y, fmtAboard(s.lifeTrainTicks()), "gui.dungeontrain.death.narr.lbl_total_aboard");
            drawCell(g, left + third + third / 2, y, Long.toString(s.lifeCarriages()), "gui.dungeontrain.death.narr.lbl_carriages");
            drawCell(g, left + 2 * third + third / 2, y, fmtDist(s.lifeDistance()), "gui.dungeontrain.death.narr.lbl_distance");
            int y2 = y + 30;
            drawCell(g, left + third / 2, y2, Long.toString(s.lifeDeaths()), "gui.dungeontrain.death.narr.lbl_deaths");
            drawCell(g, left + third + third / 2, y2, Long.toString(s.lifeFriends()), "gui.dungeontrain.death.narr.lbl_friends");
            drawCell(g, left + 2 * third + third / 2, y2, Long.toString(s.lifeBooks()), "gui.dungeontrain.death.narr.lbl_books");
            y = y2 + 30;
        }
        return y;
    }

    private int drawSurvey(GuiGraphics g, SurveyQuestionPayload.Entry e, int left, int w, int cx, int y) {
        scoreRects.clear();
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_ledger");
        y += 16;
        y = drawCentered(g, Component.translatable("gui.dungeontrain.death.narr.survey_intro"), cx, w, y, NARR);
        y += 6;
        if (e != null) {
            y = drawQuestion(g, e.prompt(), cx, w, y);
            y += 4;
            int n = e.scaleMax() - e.scaleMin() + 1;
            if (n < 1) n = 1;
            int tile = 22, gap = 4;
            int rowW = n * tile + (n - 1) * gap;
            int sx = cx - rowW / 2;
            int selected = scores.getOrDefault(e.id(), -1);
            for (int i = 0; i < n; i++) {
                int score = e.scaleMin() + i;
                int tx = sx + i * (tile + gap);
                boolean sel = selected == score;
                g.fill(tx, y, tx + tile, y + tile, sel ? BTN_PRI_BG : SCORE_BG);
                drawBorder(g, tx, y, tile, tile, sel ? BTN_PRI_LIGHT : SCORE_BORDER);
                drawCenteredStr(g, Integer.toString(score), tx + tile / 2, y + (tile - this.font.lineHeight) / 2 + 1, sel ? 0xFFFFFFFF : SCORE_TEXT);
                scoreRects.add(new Rect(tx, y, tile, tile));
            }
            y += tile + 8;
            if (e.allowComment() && commentBox != null) {
                int boxW = Math.min(w, 256);
                commentBox.setX(cx - boxW / 2);
                commentBox.setY(y);
                commentBox.setWidth(boxW);
                y += 16 + 6;
            }
        }
        return y;
    }

    private int drawPlatform(GuiGraphics g, DeathNarrative n, int left, int w, int cx, int y) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_platform");
        y += 16;
        y = drawQuestion(g, n.platformQuestion(), cx, w, y);
        y = drawNarration(g, n.platformNarration(), cx, w, y);
        if (!n.platformEpitaph().isEmpty()) {
            y += 6;
            y = drawCentered(g, Component.literal(n.platformEpitaph()), cx, w, y, SUBLINE);
        }
        return y;
    }

    // ---- Chrome ----

    private void drawTopBar(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, Component.translatable("gui.dungeontrain.death.narr.brand"),
                12, 10, 0xFF7A828C, false);
        // Right-aligned: [reboard] [leave]. leave on the far right.
        Component leave = Component.translatable("gui.dungeontrain.death.leave");
        Component reboard = Component.translatable("gui.dungeontrain.death.reboard");
        int leaveW = this.font.width(leave) + 16;
        int reboardW = this.font.width(reboard) + 16;
        int leaveX = this.width - 10 - leaveW;
        int reboardX = leaveX - 6 - reboardW;
        leaveRect = drawChip(g, leaveX, 8, leave, CHIP_LV_BORDER, CHIP_LV_TEXT);
        reboardRect = drawChip(g, reboardX, 8, reboard, CHIP_RB_BORDER, CHIP_RB_TEXT);
    }

    private void drawFooter(GuiGraphics g, Page page, int mouseX, int mouseY) {
        // Page dots.
        int n = pages.size();
        int dotGap = 11, dotsW = n * dotGap - (dotGap - 7);
        int dx = this.width / 2 - dotsW / 2;
        int dotY = this.height - 40;
        for (int i = 0; i < n; i++) {
            int c = i == currentPage ? DOT_ON : DOT_OFF;
            g.fill(dx + i * dotGap, dotY, dx + i * dotGap + 7, dotY + 7, c);
        }

        int rowY = this.height - 28;
        backRect = null;
        continueRect = null;
        boardAnewRect = null;
        platformLeaveRect = null;

        if (page.kind() == Kind.PLATFORM) {
            // Board anew + Leave, side by side, centered.
            Component anew = Component.translatable("gui.dungeontrain.death.board_anew");
            Component leave = Component.translatable("gui.dungeontrain.death.leave_line");
            int bw = 110, h = 18, gap = 8;
            int total = bw * 2 + gap;
            int bx = this.width / 2 - total / 2;
            boardAnewRect = drawBevel(g, bx, rowY, bw, h, anew, BTN_PRI_BG, BTN_PRI_LIGHT, BTN_DARK, 0xFFFFFFFF);
            platformLeaveRect = drawBevel(g, bx + bw + gap, rowY, bw, h, leave, BTN_BG, BTN_LIGHT, BTN_DARK, BTN_TEXT);
        } else {
            // Centered Continue with a bare back-arrow to its left.
            Component cont = Component.translatable("gui.dungeontrain.death.continue");
            int bw = 120, h = 18;
            int bx = this.width / 2 - bw / 2;
            continueRect = drawBevel(g, bx, rowY, bw, h, cont, BTN_BG, BTN_LIGHT, BTN_DARK, BTN_TEXT);
        }

        // Bare back-arrow (icon only), left of the button row — hidden on page 0.
        if (currentPage > 0) {
            int ax = this.width / 2 - (page.kind() == Kind.PLATFORM ? 110 : 60) - 22;
            boolean hover = mouseX >= ax - 4 && mouseX < ax + 14 && mouseY >= rowY && mouseY < rowY + 18;
            g.drawString(this.font, "←", ax, rowY + 5, hover ? QUESTION : 0xFF8A909A, false);
            backRect = new Rect(ax - 4, rowY, 22, 18);
        }
    }

    // ---- Click handling ----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (reboardRect != null && reboardRect.has(mx, my)) { boardAnew(); return true; }
            if (leaveRect != null && leaveRect.has(mx, my)) { leave(); return true; }
            Page page = pages.isEmpty() ? Page.of(Kind.FALL) : pages.get(currentPage);
            if (page.kind() == Kind.PLATFORM) {
                if (boardAnewRect != null && boardAnewRect.has(mx, my)) { boardAnew(); return true; }
                if (platformLeaveRect != null && platformLeaveRect.has(mx, my)) { leave(); return true; }
            } else if (continueRect != null && continueRect.has(mx, my)) {
                advance();
                return true;
            }
            if (backRect != null && backRect.has(mx, my)) { back(); return true; }
            if (page.kind() == Kind.SURVEY && page.survey() != null) {
                for (int i = 0; i < scoreRects.size(); i++) {
                    if (scoreRects.get(i).has(mx, my)) {
                        scores.put(page.survey().id(), page.survey().scaleMin() + i);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void advance() {
        if (pages.isEmpty()) return;
        Page page = pages.get(currentPage);
        if (page.kind() == Kind.SURVEY) maybeSubmit(page.survey());
        if (currentPage < pages.size() - 1) {
            currentPage++;
            rebuildWidgets();
        }
    }

    private void back() {
        if (currentPage > 0) {
            currentPage--;
            rebuildWidgets();
        }
    }

    private void maybeSubmit(SurveyQuestionPayload.Entry e) {
        if (e == null) return;
        int score = scores.getOrDefault(e.id(), -1);
        if (score < 0 || submitted.contains(e.id())) return;
        DPNetwork.sendToServer(new SurveySubmitPayload(e.id(), score, comments.getOrDefault(e.id(), "")));
        submitted.add(e.id());
    }

    private void boardAnew() {
        DeathScreenLayoutHandler.launchWorld(this, false);
    }

    private void leave() {
        DeathScreenLayoutHandler.goToTitleScreen();
    }

    // ---- Draw helpers ----

    private void drawKicker(GuiGraphics g, int cx, int y, String key) {
        drawCenteredStr(g, Component.translatable(key), cx, y, KICKER);
    }

    private void drawSecLabel(GuiGraphics g, int cx, int y, String key) {
        drawCenteredStr(g, Component.translatable(key), cx, y, KICKER);
    }

    private int drawQuestion(GuiGraphics g, String text, int cx, int w, int y) {
        if (text == null || text.isEmpty()) return y;
        return drawCentered(g, Component.literal(text), cx, w, y + 2, QUESTION) + 2;
    }

    private int drawNarration(GuiGraphics g, String text, int cx, int w, int y) {
        if (text == null || text.isEmpty()) return y;
        return drawCentered(g, Component.literal(text), cx, w, y + 4, NARR);
    }

    private int drawCentered(GuiGraphics g, Component text, int cx, int w, int y, int color) {
        List<FormattedCharSequence> lines = this.font.split(text, w - 8);
        for (FormattedCharSequence line : lines) {
            int lw = this.font.width(line);
            g.drawString(this.font, line, cx - lw / 2, y, color, false);
            y += this.font.lineHeight + 2;
        }
        return y;
    }

    private void drawCell(GuiGraphics g, int centerX, int y, String value, String labelKey) {
        int cw = 96, ch = 26;
        int x = centerX - cw / 2;
        g.fill(x, y, x + cw, y + ch, TILE_BG);
        drawBorder(g, x, y, cw, ch, TILE_BORDER);
        drawCenteredStr(g, value, centerX, y + 4, VALUE);
        drawCenteredStr(g, Component.translatable(labelKey), centerX, y + 4 + this.font.lineHeight + 1, LABEL);
    }

    /**
     * The infinite train, scrolled forward by {@code advance} screens — each
     * page nudges the carriages further left so the line visibly keeps moving.
     * Cars are drawn as a continuous row clamped to the rail; the ∞ sits at the
     * far right as the destination that never arrives.
     */
    private void drawTrain(GuiGraphics g, int left, int w, int y, int advance) {
        int railY = y + 30;
        g.fill(left + 2, railY, left + w - 2, railY + 2, RAIL);
        int carW = 22, carH = 14, gap = 4, spacing = carW + gap;
        int rightEdge = left + w - 14;            // leave room for the ∞
        int offset = Math.max(0, advance) * 18;   // each screen advances the train forward
        for (int k = offset / spacing - 2; ; k++) {
            int cxp = left + 6 + k * spacing - offset;
            if (cxp > rightEdge) break;
            if (cxp + carW < left) continue;
            int x0 = Math.max(cxp, left);
            int x1 = Math.min(cxp + carW, rightEdge);
            if (x1 <= x0) continue;
            g.fill(x0, railY - carH, x1, railY, 0xFF33353E);
            g.fill(x0, railY - carH, x1, railY - carH + 2, RED);
            if (cxp >= left && cxp + carW <= rightEdge) {
                g.fill(cxp + 4, railY - carH + 4, cxp + 9, railY - carH + 9, 0xFF14151A);
                g.fill(cxp + 13, railY - carH + 4, cxp + 18, railY - carH + 9, 0xFF14151A);
            }
        }
        g.drawString(this.font, "∞", left + w - 12, railY - 8, INF, false);
    }

    private void drawLoadout(GuiGraphics g, DeathStatsPacket s, int left, int w, int y, int mouseX, int mouseY) {
        ItemStack[] gear = { s.mostUsedWeapon(), s.armorHead(), s.armorChest(), s.armorLegs(), s.armorFeet() };
        int gap = 6;
        int rowW = gear.length * SLOT + (gear.length - 1) * gap;
        int sx = left + (w - rowW) / 2;
        for (int i = 0; i < gear.length; i++) {
            int x = sx + i * (SLOT + gap);
            g.fill(x, y, x + SLOT, y + SLOT, SLOT_BG);
            g.fill(x, y, x + SLOT, y + 1, SLOT_DARK);
            g.fill(x, y, x + 1, y + SLOT, SLOT_DARK);
            g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, SLOT_LIGHT);
            g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, SLOT_LIGHT);
            ItemStack stack = gear[i];
            if (!stack.isEmpty()) {
                g.renderItem(stack, x + 1, y + 1);
                g.renderItemDecorations(this.font, stack, x + 1, y + 1);
            }
        }
    }

    private void drawLoadoutTooltip(GuiGraphics g, DeathStatsPacket s, int left, int w, int mouseX, int mouseY) {
        if (loadoutY < 0) return;
        ItemStack[] gear = { s.mostUsedWeapon(), s.armorHead(), s.armorChest(), s.armorLegs(), s.armorFeet() };
        int gap = 6;
        int rowW = gear.length * SLOT + (gear.length - 1) * gap;
        int sx = left + (w - rowW) / 2;
        for (int i = 0; i < gear.length; i++) {
            int x = sx + i * (SLOT + gap);
            ItemStack stack = gear[i];
            if (stack.isEmpty()) continue;
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= loadoutY && mouseY < loadoutY + SLOT) {
                g.renderTooltip(this.font, Screen.getTooltipFromItem(Minecraft.getInstance(), stack),
                        stack.getTooltipImage(), mouseX, mouseY);
            }
        }
    }

    private int loadoutY = -1;

    private Rect drawChip(GuiGraphics g, int x, int y, Component text, int border, int textColor) {
        int w = this.font.width(text) + 16, h = 14;
        g.fill(x, y, x + w, y + h, 0x66000000);
        drawBorder(g, x, y, w, h, border);
        g.drawString(this.font, text, x + 8, y + (h - this.font.lineHeight) / 2 + 1, textColor, false);
        return new Rect(x, y, w, h);
    }

    private Rect drawBevel(GuiGraphics g, int x, int y, int w, int h, Component text,
                           int bg, int light, int dark, int textColor) {
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y, x + w, y + 2, light);
        g.fill(x, y, x + 2, y + h, light);
        g.fill(x, y + h - 2, x + w, y + h, dark);
        g.fill(x + w - 2, y, x + w, y + h, dark);
        drawCenteredStr(g, text, x + w / 2, y + (h - this.font.lineHeight) / 2 + 1, textColor);
        return new Rect(x, y, w, h);
    }

    private void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void drawCenteredStr(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }

    private void drawCenteredStr(GuiGraphics g, Component c, int cx, int y, int color) {
        g.drawString(this.font, c, cx - this.font.width(c) / 2, y, color, false);
    }

    // ---- Formatters ----

    private static String fmtDist(double blocks) {
        return String.format(java.util.Locale.ROOT, "%,.0f m", blocks);
    }

    private static String fmtTime(long ticks) {
        long t = ticks / 20L, h = t / 3600L, m = (t % 3600L) / 60L, sec = t % 60L;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    private static String fmtAboard(long ticks) {
        long t = ticks / 20L, h = t / 3600L, m = (t % 3600L) / 60L;
        return h > 0 ? (h + "h " + m + "m") : (m + "m");
    }

    private static String fmtDmg(double hp) {
        if (hp >= 1_000_000.0) return String.format(java.util.Locale.ROOT, "%.1fM", hp / 1_000_000.0);
        if (hp >= 10_000.0) return String.format(java.util.Locale.ROOT, "%.1fk", hp / 1_000.0);
        return String.format(java.util.Locale.ROOT, "%,.0f", hp);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
