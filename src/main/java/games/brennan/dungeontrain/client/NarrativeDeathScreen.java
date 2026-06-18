package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.SurveyClientState;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.discordpresence.network.SurveySubmitPayload;
import games.brennan.dungeontrain.net.DeathNarrative;
import games.brennan.dungeontrain.client.sound.TrainEngineSound;
import games.brennan.dungeontrain.client.snapshot.DeathBackgroundPainter;
import games.brennan.dungeontrain.client.snapshot.RideSnapshot;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotGallery;
import games.brennan.dungeontrain.client.snapshot.SnapshotTag;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

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
    private static final int OVERLAY        = 0xF2090A0D;
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
    private static final int SCORE_BG       = 0xFF1A1813;
    private static final int SCORE_BORDER   = 0xFF4A443C;
    private static final int SCORE_TEXT     = 0xFFCDBB95;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_CONTENT_W = 360;
    private static final int SLOT = 18;

    // DEEDS-page portrait of the befriended/killed PlayerMob, drawn beside the stat grid.
    private static final int PORTRAIT_W = 64;
    private static final int PORTRAIT_GUTTER = 8;
    private static final int PORTRAIT_RESERVE = PORTRAIT_W + PORTRAIT_GUTTER;
    /** Below this content width the portrait is skipped (grid stays full-width). */
    private static final int PORTRAIT_MIN_CONTENT_W = 260;

    private final Map<String, Integer> scores = new HashMap<>();
    private final Map<String, String> comments = new HashMap<>();
    private final Set<String> submitted = new HashSet<>();

    private List<Page> pages = List.of();
    private int currentPage = 0;
    private int lastSurveyCount = -1;
    private EditBox commentBox;

    // Cached per-page ride-photo background. Re-picked only when the page changes
    // so the random survey backdrop doesn't reshuffle every frame.
    private int bgForPage = -1;
    private RideSnapshot bgShot;

    /** Renders the DEEDS-page befriended/killed PlayerMob portrait; cleared on removal. */
    private final DeathPortraitRenderer portrait = new DeathPortraitRenderer();

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
        LOGGER.info("[DungeonTrain] NarrativeDeathScreen: page {}/{}, surveyQuestions={}, statsCached={}",
                currentPage, pages.size(), lastSurveyCount, DeathStatsCache.get() != null);

        Page p = pages.get(currentPage);
        if (p.kind() == Kind.SURVEY && p.survey() != null && p.survey().allowComment()) {
            commentBox = new EditBox(this.font, 0, 0, 100, 16,
                    Component.translatable("gui.dungeontrain.death.narr.comment"));
            commentBox.setMaxLength(256);
            String qid = p.survey().id();
            commentBox.setValue(comments.getOrDefault(qid, ""));
            commentBox.setResponder(s -> comments.put(qid, s));
            // A text question (no rating scale) makes the box the sole answer, so prompt for it
            // directly; a scale question's comment just explains the score.
            boolean hasScale = p.survey().scaleMax() >= p.survey().scaleMin();
            commentBox.setHint(Component.translatable(hasScale
                    ? "gui.dungeontrain.death.narr.comment"
                    : "gui.dungeontrain.death.narr.answer"));
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
        DeathStatsPacket stats = DeathStatsCache.get();
        DeathNarrative narr = stats != null ? stats.narrative() : DeathNarrative.EMPTY;
        Page page = pages.isEmpty() ? Page.of(Kind.FALL) : pages.get(currentPage);

        // Backdrop: this run's third-person ride photos, one per page (random behind
        // survey), under a legibility vignette. Falls back to the solid overlay when
        // the feature is off or no photos were captured this run.
        RideSnapshot bg = backgroundFor(page);
        if (bg != null) {
            DeathBackgroundPainter.draw(g, bg, this.width, this.height);
        } else {
            g.fill(0, 0, this.width, this.height, OVERLAY);
        }

        // Train engine: full on the first screen (as if aboard), fading evenly to
        // silence by the last screen — and rising again if the player steps back.
        TrainEngineSound.deathScreenActive = true;
        TrainEngineSound.deathFade = pages.size() > 1
                ? Math.max(0.0f, 1.0f - (float) currentPage / (pages.size() - 1))
                : 1.0f;

        // Clickable regions are set by the page body / footer each frame; clear
        // any from last frame so a page that doesn't draw one can't be clicked.
        boardAnewRect = null;
        platformLeaveRect = null;
        continueRect = null;
        backRect = null;

        int contentW = Math.min(MAX_CONTENT_W, this.width - 40);
        int cx = this.width / 2;
        int left = cx - contentW / 2;

        drawTopBar(g, mouseX, mouseY);

        int y = 40;
        switch (page.kind()) {
            case FALL -> y = drawFall(g, stats, narr, left, contentW, cx, y, mouseX, mouseY);
            case DEEDS -> y = drawDeeds(g, stats, narr, left, contentW, cx, y, partialTick);
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

    /**
     * The cached ride-photo background for the current page, re-picked only when
     * the page changes. Each data page prefers its most relevant shot; the survey
     * page draws a random one. {@code null} = no photos (or feature off) → the
     * original solid overlay is used instead.
     */
    private RideSnapshot backgroundFor(Page page) {
        if (!ClientDisplayConfig.isRideSnapshotsEnabled() || RideSnapshotGallery.isEmpty()) {
            return null;
        }
        if (bgForPage != currentPage) {
            bgForPage = currentPage;
            bgShot = switch (page.kind()) {
                case FALL     -> DeathBackgroundPainter.pick(SnapshotTag.SCENIC, false);
                case DEEDS    -> DeathBackgroundPainter.pick(SnapshotTag.COMBAT, false);
                case GEAR     -> DeathBackgroundPainter.pick(SnapshotTag.GEAR, false);
                case LIVES    -> DeathBackgroundPainter.pick(SnapshotTag.SOCIAL, false);
                case SURVEY   -> DeathBackgroundPainter.pick(null, true);
                case PLATFORM -> DeathBackgroundPainter.pick(null, false);
            };
        }
        return bgShot;
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
        y += 10;
        if (s != null) {
            int third = w / 3;
            drawCell(g, left + third / 2, y, Integer.toString(s.cartsTravelled()), "gui.dungeontrain.death.narr.lbl_carriage");
            drawCell(g, left + third + third / 2, y, fmtDist(s.distanceBlocks()), "gui.dungeontrain.death.narr.lbl_distance");
            drawCell(g, left + 2 * third + third / 2, y, fmtTime(s.runTicks()), "gui.dungeontrain.death.narr.lbl_time");
        }
        return y + 34;
    }

    private int drawDeeds(GuiGraphics g, DeathStatsPacket s, DeathNarrative n,
                          int left, int w, int cx, int y, float partialTick) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_deeds");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawQuestion(g, n.deedsQuestion(), cx, w, y);
        y = drawNarration(g, n.deedsNarration(), cx, w, y);
        y += 10;
        if (s != null) {
            // A portrait of the PlayerMob this run was "about" — befriended (left)
            // or, failing that, killed (right) — sits beside the 3×2 stat grid,
            // which shrinks to make room. side 0 (neither) keeps the full-width grid.
            boolean showPortrait = s.side() != 0 && w >= PORTRAIT_MIN_CONTENT_W && portrait.available();
            int gridLeft = left;
            int gridW = w;
            if (showPortrait) {
                gridW = w - PORTRAIT_RESERVE;
                if (s.side() == 1) gridLeft = left + PORTRAIT_RESERVE; // friend → portrait on the left
                // side == 2 (killed) → portrait on the right; grid stays at left
            }
            int third = gridW / 3;
            int cw = Math.min(96, third - 8);
            // Row 1
            drawCell(g, gridLeft + third / 2, y, Integer.toString(s.mobKills()), "gui.dungeontrain.death.narr.lbl_mobs", cw);
            drawCell(g, gridLeft + third + third / 2, y, Integer.toString(s.playersEncountered()), "gui.dungeontrain.death.narr.lbl_met", cw);
            drawCell(g, gridLeft + 2 * third + third / 2, y, Integer.toString(s.playersKilled()), "gui.dungeontrain.death.narr.lbl_slain", cw);
            // Row 2
            int y2 = y + 30;
            drawCell(g, gridLeft + third / 2, y2, Integer.toString(s.playersBefriended()), "gui.dungeontrain.death.narr.lbl_befriended", cw);
            drawCell(g, gridLeft + third + third / 2, y2, fmtDmg(s.damageDealt()), "gui.dungeontrain.death.narr.lbl_dealt", cw);
            drawCell(g, gridLeft + 2 * third + third / 2, y2, fmtDmg(s.damageTaken()), "gui.dungeontrain.death.narr.lbl_taken", cw);
            // Full-body portrait beside the grid: feet pinned at row-2 bottom, drawn
            // on top so the head rises above row 1 (uncropped); name labelled below.
            if (showPortrait && s.portrait() != null) {
                int feetY = y2 + 26;
                int px1 = s.side() == 1 ? left : left + gridW + PORTRAIT_GUTTER;
                int pcx = px1 + PORTRAIT_W / 2;
                portrait.render(g, s.portrait(), s.side(), pcx, feetY, partialTick);
                drawPortraitName(g, s.portrait().name(), pcx, feetY + 2, PORTRAIT_W + 2 * PORTRAIT_GUTTER);
            }
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
            this.loadoutY = y;
            drawLoadout(g, s, left, w, y, mouseX, mouseY);
            y += SLOT + 10;
            // Cargo tallies under the loadout: loot containers opened and books
            // read this run.
            drawCell(g, cx - 52, y, Integer.toString(s.containersOpened()), "gui.dungeontrain.death.narr.lbl_loot");
            drawCell(g, cx + 52, y, Integer.toString(s.booksRead()), "gui.dungeontrain.death.narr.lbl_books");
            y += 30;
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
            y = drawCentered(g, styled(n.livesSubline()), cx, w, y, SUBLINE);
            y += 2;
        }
        y = drawNarration(g, n.livesNarration(), cx, w, y);
        y += 10;
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
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawCentered(g, Component.translatable("gui.dungeontrain.death.narr.survey_intro"), cx, w, y, NARR);
        y += 6;
        if (e != null) {
            y = drawQuestion(g, e.prompt(), cx, w, y);
            y += 4;
            // The 0–N rating row — only for scale questions. A text question
            // (scaleMax < scaleMin) shows no tiles; its answer box is the sole input.
            if (e.scaleMax() >= e.scaleMin()) {
                int n = e.scaleMax() - e.scaleMin() + 1;
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
            }
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
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        y = drawQuestion(g, n.platformQuestion(), cx, w, y);
        y = drawNarration(g, n.platformNarration(), cx, w, y);
        if (!n.platformEpitaph().isEmpty()) {
            y += 6;
            y = drawCentered(g, styled(n.platformEpitaph()), cx, w, y, SUBLINE);
        }
        y += 14;
        // Board anew — the prominent action, front and centre under the epitaph.
        int baW = 180, baH = 22;
        boardAnewRect = drawBevel(g, cx - baW / 2, y, baW, baH,
                Component.translatable("gui.dungeontrain.death.board_anew"),
                BTN_PRI_BG, BTN_PRI_LIGHT, BTN_DARK, 0xFFFFFFFF);
        y += baH + 8;
        // Leave the line — smaller, secondary, beneath it.
        int lvW = 116, lvH = 15;
        platformLeaveRect = drawBevel(g, cx - lvW / 2, y, lvW, lvH,
                Component.translatable("gui.dungeontrain.death.leave_line"),
                BTN_BG, BTN_LIGHT, BTN_DARK, BTN_TEXT);
        return y + lvH + 6;
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
        int rowY = this.height - 28;

        // Continue (centered) on every page except the platform, where Board anew /
        // Leave live in the page body instead.
        if (page.kind() != Kind.PLATFORM) {
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
        if (e == null || submitted.contains(e.id())) return;
        String comment = comments.getOrDefault(e.id(), "").trim();
        int score;
        if (e.scaleMax() >= e.scaleMin()) {
            // Scale question: needs a chosen rating; the comment stays optional.
            score = scores.getOrDefault(e.id(), -1);
            if (score < 0) return;
        } else {
            // Text question: the typed answer IS the submission — send nothing until it's entered.
            if (comment.isEmpty()) return;
            score = 0; // unused server-side (DP omits the Rating field for no-scale questions)
        }
        DPNetwork.sendToServer(new SurveySubmitPayload(e.id(), score, comment));
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
        return drawCentered(g, styled(text), cx, w, y + 2, QUESTION) + 2;
    }

    private int drawNarration(GuiGraphics g, String text, int cx, int w, int y) {
        if (text == null || text.isEmpty()) return y;
        return drawCentered(g, styled(text), cx, w, y + 4, NARR);
    }

    private static final char NUM_START = '';
    private static final char NUM_END = '';

    /**
     * Build a Component from a narration string, colouring the spans the server
     * wrapped in number-sentinels white so the figures pop against the muted
     * narration. Plain strings (no sentinels) pass straight through.
     */
    private Component styled(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (raw.indexOf(NUM_START) < 0) return Component.literal(raw);
        MutableComponent out = Component.empty();
        StringBuilder buf = new StringBuilder();
        boolean inNum = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == NUM_START) {
                flushPiece(out, buf, false);
                inNum = true;
            } else if (c == NUM_END) {
                flushPiece(out, buf, true);
                inNum = false;
            } else {
                buf.append(c);
            }
        }
        flushPiece(out, buf, inNum);
        return out;
    }

    private void flushPiece(MutableComponent out, StringBuilder buf, boolean white) {
        if (buf.length() == 0) return;
        MutableComponent piece = Component.literal(buf.toString());
        if (white) piece.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF)));
        out.append(piece);
        buf.setLength(0);
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
        drawCell(g, centerX, y, value, labelKey, 96);
    }

    private void drawCell(GuiGraphics g, int centerX, int y, String value, String labelKey, int cw) {
        int ch = 26;
        int x = centerX - cw / 2;
        g.fill(x, y, x + cw, y + ch, TILE_BG);
        drawBorder(g, x, y, cw, ch, TILE_BORDER);
        drawCenteredStr(g, value, centerX, y + 4, VALUE);
        drawCenteredStr(g, Component.translatable(labelKey), centerX, y + 4 + this.font.lineHeight + 1, LABEL);
    }

    /** The portrait subject's name, centered under the figure and shrunk to fit {@code maxW}. */
    private void drawPortraitName(GuiGraphics g, String name, int centerX, int topY, int maxW) {
        if (name == null || name.isEmpty()) return;
        int tw = this.font.width(name);
        float scale = tw > maxW ? (float) maxW / tw : 1.0f;
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(centerX, topY, 0.0);
        ps.scale(scale, scale, 1.0f);
        g.drawString(this.font, name, -tw / 2, 0, VALUE, false);
        ps.popPose();
    }

    /**
     * The train fading into the dark — solid carriages on the left dissolving
     * into fainter ones, then the ∞. The solid run grows with the page: a
     * single carriage on the first screen, filling the whole rail by the last,
     * where the fade falls away and only the assembled train and its ∞ remain
     * — a train that completes itself exactly as the narrative ends.
     */
    private void drawTrain(GuiGraphics g, int left, int w, int y, int advance) {
        int railY = y + 30;
        g.fill(left + 2, railY, left + w - 2, railY + 2, RAIL);
        int carW = 22, carH = 14, gap = 4, spacing = carW + gap;
        int startX = left + 6;
        int rightEdge = left + w - 14;            // leave room for the ∞
        int slots = Math.max(1, (rightEdge - startX) / spacing);
        // One carriage on the first page, scaling to a full rail of solid
        // carriages by the last. "Full" is the whole rail — the fade tail does
        // NOT count toward it, so the final screen fills completely (only the ∞
        // beyond), while earlier screens trail off into the fade.
        int pageCount = pages.size();
        boolean lastPage = advance >= pageCount - 1;
        int full = slots;
        int solid = pageCount > 1
                ? Math.round(1f + (full - 1) * (float) advance / (pageCount - 1))
                : full;
        if (solid < 1) solid = 1;
        if (solid > full) solid = full;
        for (int i = 0; i < solid; i++) {
            int cxp = startX + i * spacing;
            if (cxp + carW > rightEdge) break;
            g.fill(cxp, railY - carH, cxp + carW, railY, 0xFF33353E);
            g.fill(cxp, railY - carH, cxp + carW, railY - carH + 2, RED);
            g.fill(cxp + 4, railY - carH + 4, cxp + 9, railY - carH + 9, 0xFF14151A);
            g.fill(cxp + 13, railY - carH + 4, cxp + 18, railY - carH + 9, 0xFF14151A);
        }
        // The fade tail trails off beyond the solid run on every screen but the
        // last, where the train has filled the rail.
        if (!lastPage) {
            int[] fade = { 0x8033353E, 0x4D2B2C33, 0x2624252B };
            int fadeX = startX + solid * spacing;
            for (int j = 0; j < fade.length; j++) {
                int cxp = fadeX + j * spacing;
                int fw = Math.min(cxp + carW, rightEdge);
                if (fw <= cxp) break;
                int fh = carH - 2 - j * 3;
                if (fh < 5) fh = 5;
                g.fill(cxp, railY - fh, fw, railY, fade[j]);
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
        // Like the vanilla death screen, do NOT pause — so the world keeps
        // ticking and the train engine keeps sounding behind the screen (which
        // we then fade out page by page).
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void removed() {
        // Screen going away (Board anew / Leave / replaced) — hand the train back
        // to its normal world-driven volume.
        TrainEngineSound.deathScreenActive = false;
        TrainEngineSound.deathFade = 1.0f;
        portrait.clear();
    }
}
