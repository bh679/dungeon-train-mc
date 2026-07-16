package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.SurveyClientState;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.discordpresence.survey.SurveyKeys;
import games.brennan.discordpresence.network.SurveySubmitPayload;
import games.brennan.dungeontrain.net.DeathNarrative;
import games.brennan.dungeontrain.net.DeathPhotoPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.client.sound.TrainEngineSound;
import games.brennan.dungeontrain.client.snapshot.DeathBackgroundAssigner;
import games.brennan.dungeontrain.client.snapshot.DeathBackgroundPainter;
import games.brennan.dungeontrain.client.snapshot.RideGalleryScreen;
import games.brennan.dungeontrain.client.snapshot.RideSnapshot;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotGallery;
import games.brennan.dungeontrain.client.snapshot.SnapshotTag;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import net.minecraft.Util;
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
import net.minecraft.world.item.Items;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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
 * <i>Next Screen</i> advances; a bare back-arrow returns; <i>reboard</i> and
 * <i>leave the line</i> sit in the top corner throughout.</p>
 *
 * <p>Moving between pages plays a slow transition: the chrome + text + vignette
 * fade out over the held photo (fully revealing it), the bare photo holds for a
 * beat, then the next page's UI fades in while the photo dips to black and the
 * next one rises from black beneath it. Settled pages darken the vignette for
 * legibility; the bare-photo hold drops it entirely. A click or Space skips a
 * running fade. Driven by per-frame {@link #uiAlpha} + the photo dip
 * ({@link #photoBlack}/{@link #photoNew}) and a wall-clock timer.</p>
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

    /** A resolved advancement on the GEAR page: vanilla display data + its on-screen rect (for hover). */
    private record AdvIcon(ItemStack icon, Component title, Component description, AdvancementType type, Rect rect) {}

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
    // Shift-held "Quit Game" variant of the leave chip: darker bg, lighter border + text.
    private static final int CHIP_LV_QUIT_BG     = 0x99000000;
    private static final int CHIP_LV_QUIT_BORDER = 0xFF6A6F78;
    private static final int CHIP_LV_QUIT_TEXT   = 0xFFD8DCE2;
    private static final int CHIP_PH_BORDER = 0xFF5A5236;
    private static final int CHIP_PH_TEXT   = 0xFFC9B98A;
    private static final int BTN_BG         = 0xFF585B5E;
    private static final int BTN_LIGHT      = 0xFF76797D;
    private static final int BTN_DARK       = 0xFF1E1F21;
    private static final int BTN_TEXT       = 0xFFEAEAEA;
    // Shift-held "Quit Game" variant of the bevel button: darker bg, lighter bevel + text.
    private static final int BTN_QUIT_BG    = 0xFF3A3C3F;
    private static final int BTN_QUIT_LIGHT = 0xFF9CA0A6;
    private static final int BTN_QUIT_DARK  = 0xFF26272A;
    private static final int BTN_QUIT_TEXT  = 0xFFFFFFFF;
    private static final int BTN_PRI_BG     = 0xFF3C6B41;
    private static final int BTN_PRI_LIGHT  = 0xFF5C9162;
    // Red "Submit Bug" shortcut button (start page, beside Next Screen).
    private static final int BTN_BUG_BG     = 0xFF8B3A3A;
    private static final int BTN_BUG_LIGHT  = 0xFFB45C5C;
    private static final int BTN_BUG_TEXT   = 0xFFFFFFFF;
    // Faded-green "Bug Submitted" status variant: the primary-green bevel at ~80% alpha
    // (0xCC). drawBevel→fade() then scales by uiAlpha, so it lands at ~80% once settled.
    private static final int BTN_DONE_BG    = 0xCC3C6B41;
    private static final int BTN_DONE_LIGHT = 0xCC5C9162;
    private static final int BTN_DONE_DARK  = 0xCC1E1F21;
    private static final int BTN_DONE_TEXT  = 0xCCFFFFFF;
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

    // ---- Page-to-page transition ----
    // Each page change plays: fade the UI out over the held photo, hold the bare
    // photo, then fade the new UI in while the photo dips to black and rises back.
    private static final long T_FADE = 350L;       // length of each UI fade (out, then in)
    private static final long T_HOLD = 500L;       // bare-photo hold between the two fades
    private static final long T_DIP_DOWN = T_FADE;  // photo dims to black, synced with the UI fade-in
    private static final long T_DIP_UP   = 10_000L; // photo rises back in from black — a slow 10s reveal
    // uiAlpha at/above this counts as "settled": elements that can't take an
    // alpha tint (item icons, the comment box) only show once the page is settled.
    private static final float SETTLED = 0.95f;
    // Below this UI alpha the UI is skipped entirely (see render): Minecraft's font
    // renderer forces text with alpha < ~4/255 to fully opaque, so drawing it at ~0
    // alpha flashes it solid. Above this the lowest text alpha drawn is a safe ~5/255.
    private static final float UI_EPS = 0.02f;

    // The bug-report survey question (data-driven dp_surveys/bug_report.json): a multiple-choice
    // question whose non-"No" answers trigger client-side log collection + a BugReportLogsPacket.
    // The id and the collect-and-ship logic live in the shared {@link BugLogReporter} (also used
    // by the on-demand /bug + /feedback survey).
    private static final String BUG_REPORT_ID = BugLogReporter.BUG_REPORT_ID;

    private final Map<String, Integer> scores = new HashMap<>();
    private final Map<String, String> comments = new HashMap<>();
    private final Set<String> submitted = new HashSet<>();

    private List<Page> pages = List.of();
    private int currentPage = 0;
    private int lastSurveyCount = -1;
    // Plea variants shown above the 2nd (and any later) survey question. The 1st question keeps the
    // calm "ledger" intro; the 2nd swaps to one of these urgent pleas, chosen at random per death
    // (see surveyIntro2Choice).
    private static final String[] SURVEY_INTRO_2_KEYS = {
            "gui.dungeontrain.death.narr.survey_intro_2_1",
            "gui.dungeontrain.death.narr.survey_intro_2_2",
            "gui.dungeontrain.death.narr.survey_intro_2_3",
            "gui.dungeontrain.death.narr.survey_intro_2_4",
            "gui.dungeontrain.death.narr.survey_intro_2_5",
            "gui.dungeontrain.death.narr.survey_intro_2_6",
            "gui.dungeontrain.death.narr.survey_intro_2_7",
    };
    // Index into SURVEY_INTRO_2_KEYS. Picked once, lazily, the first time a 2nd survey page renders —
    // deliberately NOT reset in init(), so it stays stable across paging / re-renders within one
    // death (a new death = new screen instance = fresh pick). -1 = not yet picked.
    private int surveyIntro2Choice = -1;
    private EditBox commentBox;
    private boolean opened = false;
    private boolean photoSent = false;

    // Transition state. transStartMs == 0 means "settled, nothing animating".
    private long transStartMs = 0L;
    private int pendingPage = -1;          // page to switch to once the fade-out ends
    private boolean swapped = false;       // page switch already applied this transition
    private boolean pendingInitialFade = false; // seed the opening fade-in on first frame
    private RideSnapshot fromShot, toShot; // held (old) photo, and the one we switch to
    private float uiAlpha = 0.0f;          // 0..1 — how present the chrome/text is this frame
    private float photoBlack = 0.0f;       // 0..1 — black fill laid over the photo (the dip)
    private boolean photoNew = false;      // show the incoming photo (true) vs the held one (false)
    // A skip during the UI fade hands the still-dark photo to a quick reveal
    // (fast-track) rather than the full T_DIP_UP or an instant snap.
    private long imgFinishMs = 0L;         // start of the fast image reveal (0 = none)
    private float imgFinishFrom = 0.0f;    // photoBlack when the fast reveal began
    // True only while the chrome is mid-fade (fade-out / hold / fade-in). The slow
    // image rise afterwards is NOT busy: a click then advances rather than skipping.
    private boolean uiBusy = false;
    // photoBlack a transition inherits at its start; if it's > 0 (advancing mid-rise)
    // the photo finishes rising in during the fade-out instead of snapping to full.
    private float dipStartBlack = 0.0f;

    // One ride photo per page, assigned up-front (unused-first) so pages don't
    // repeat a shot. Empty when the feature is off / no photos were captured.
    private static final RideSnapshot[] NO_BACKGROUNDS = new RideSnapshot[0];
    private RideSnapshot[] pageBackgrounds = NO_BACKGROUNDS;

    /** Renders the DEEDS-page befriended/killed PlayerMob portrait; cleared on removal. */
    private final DeathPortraitRenderer portrait = new DeathPortraitRenderer();

    // Clickable regions, recomputed each render() and read by mouseClicked().
    private Rect reboardRect, leaveRect, continueRect, backRect, boardAnewRect, platformLeaveRect;
    private Rect photosRect;
    // Red "Submit Bug" shortcut (start page only); null when absent or shown as the
    // non-clickable green "Bug Submitted" status.
    private Rect bugReportRect;
    // Set when the bug page is opened via the shortcut, so the next Next Screen on that
    // page returns to the start page instead of advancing forward.
    private boolean returnToStartAfterBug = false;
    private final List<Rect> scoreRects = new ArrayList<>();

    // ---- Cargo (GEAR) page: inline cargo icons + scrollable advancements row ----
    private int gearAdvScroll = 0;          // horizontal scroll offset (px) of the advancements row
    private int gearAdvMaxScroll = 0;       // scroll clamp bound, set during drawGear
    private int cargoRowY = -1;             // top y of row 1 (equipment + cargo icons), for tooltips
    private int cargoSx = -1;               // start x of the equipment slots, for tooltips
    private Rect containersRect, booksRect, writtenRect; // chest / book / written-book cargo-icon hover regions (tooltips)
    /** All-lives (LIVES) page icon-row hover regions + their tooltip keys, rebuilt each frame in {@link #drawLives}. */
    private record LifeStat(Rect rect, String tipKey) {}
    private final List<LifeStat> lifeStats = new ArrayList<>();
    /** Vanilla full-heart HUD sprite — the "hearts lost" icon (no heart item exists). */
    private static final ResourceLocation HEART_SPRITE = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private Rect seeAllRect;                // "see all advancements" button
    private Rect advViewport;               // advancements scroll viewport (hover / scroll hit-test)
    private final List<AdvIcon> gearAdvIcons = new ArrayList<>();  // resolved this frame, for hover

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
        // Freeze the gallery for as long as the death screen is up: no flush / eviction / texture
        // release may run while we're blitting these photos (a released texture would blank a page).
        RideSnapshotGallery.freeze();
        pages = buildPages();
        if (currentPage >= pages.size()) currentPage = pages.size() - 1;
        if (currentPage < 0) currentPage = 0;
        assignBackgrounds();
        maybeSendRidePhoto();
        lastSurveyCount = SurveyClientState.questions().size();
        gearAdvScroll = 0;
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
            // Editing an already-sent answer clears its "Sent" badge so the new text
            // re-submits on Next Screen. setValue above runs before this responder is
            // attached, so re-entering the page doesn't spuriously fire it.
            commentBox.setResponder(s -> { comments.put(qid, s); submitted.remove(qid); });
            // The bug question asks for a detailed free-text description; otherwise a text question
            // (no rating scale) makes the box the sole answer (prompt for it directly), and a scale
            // question's comment just explains the score.
            String hintKey;
            if (qid.equals(BUG_REPORT_ID)) {
                hintKey = "gui.dungeontrain.death.narr.bug_comment_hint";
            } else {
                boolean hasScale = p.survey().scaleMax() >= p.survey().scaleMin();
                hintKey = hasScale
                        ? "gui.dungeontrain.death.narr.comment"
                        : "gui.dungeontrain.death.narr.answer";
            }
            commentBox.setHint(Component.translatable(hintKey));
            addRenderableWidget(commentBox);
        }

        // First appearance: fade the opening page's UI up over its photo instead
        // of popping in. Just the fade-in (no fade-out, no hold, no photo switch);
        // the timer is seeded on the first rendered frame so a hitch between
        // opening and that frame can't eat the fade. Done once — later
        // rebuildWidgets() (the deferred swap, survey arrival) must not retrigger
        // it or it would reset an in-flight transition.
        if (!opened) {
            opened = true;
            pendingInitialFade = true;
            swapped = true;
            pendingPage = -1;
            fromShot = bgFor(currentPage);
            toShot = null;
        }
    }

    /**
     * Once per death, hand this run's scenic fall-page ride photo to the server ({@link DeathPhotoPacket})
     * so the top-level manifest death report can use it as its image. Sent on ALL builds (the manifest
     * now posts to the public channel on release builds, not just the dev channel) — the server only
     * uses it when it has a pending top-level report (legit deaths), so a stray send is harmless. Sends
     * an empty array when there's no photo so the server posts promptly with its gear-composite fallback
     * rather than waiting out the timeout.
     */
    private void maybeSendRidePhoto() {
        if (photoSent) return;
        photoSent = true;
        RideSnapshot fall = bgFor(0); // page 0 is FALL, assigned a SCENIC shot
        byte[] jpeg = fall != null ? fall.photoBytes() : null;
        DungeonTrainNet.sendToServer(new DeathPhotoPacket(jpeg != null ? jpeg : new byte[0]));
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
        // Advance the transition first: it may apply the deferred page switch
        // (and rebuild widgets) when the fade-out ends, so everything below sees
        // the right page.
        long now = Util.getMillis();
        if (pendingInitialFade) {
            // Seed the opening fade-in here (not in init) so a hitch between the
            // screen opening and the first frame can't eat the fade. Offsetting by
            // T_FADE + T_HOLD starts straight in the fade-in phase.
            pendingInitialFade = false;
            transStartMs = now - (T_FADE + T_HOLD);
        }
        updateTransition(now);

        DeathStatsPacket stats = DeathStatsCache.get();
        DeathNarrative narr = stats != null ? stats.narrative() : DeathNarrative.EMPTY;
        Page page = pages.isEmpty() ? Page.of(Kind.FALL) : pages.get(currentPage);

        // Backdrop: this run's third-person ride photos, one assigned per page
        // (preferring photos not used on another page). The photo draws fully
        // opaque (blit alpha is unreliable for the framebuffer textures); the
        // dip-to-black is a black fill laid over it, and the old->new swap happens
        // under full black so it isn't seen. Falls back to the solid overlay when
        // the feature is off or no photos were captured this run.
        RideSnapshot photo = photoNew && toShot != null
                ? toShot
                : (fromShot != null ? fromShot : bgFor(currentPage));
        if (photo != null) {
            DeathBackgroundPainter.drawPhoto(g, photo, this.width, this.height);
            if (photoBlack > 0.0f) {
                int a = Math.min(255, Math.round(photoBlack * 255.0f));
                g.fill(0, 0, this.width, this.height, a << 24);
            }
            DeathBackgroundPainter.drawVignette(g, this.width, this.height, uiAlpha);
        } else {
            // No photo to reveal — keep the solid overlay fully opaque (don't lay
            // bare the frozen world); the chrome still fades out/in against it.
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
        bugReportRect = null;
        containersRect = null;
        booksRect = null;
        writtenRect = null;
        lifeStats.clear();
        seeAllRect = null;
        advViewport = null;
        gearAdvIcons.clear();

        int contentW = Math.min(MAX_CONTENT_W, this.width - 40);
        int cx = this.width / 2;
        int left = cx - contentW / 2;

        // UI content (chrome + text + widgets). Skipped entirely while the page is
        // essentially invisible: Minecraft's font renderer forces text with alpha
        // < ~4/255 to fully opaque, so drawing it at ~0 alpha would flash it solid
        // during the hold and at the tail of each fade. The photo + vignette above
        // (drawn with fill, which has no such quirk) keep showing.
        if (commentBox != null) commentBox.visible = settled();
        if (uiAlpha > UI_EPS) {
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

            // Cargo-page hover tooltips last, above everything — only when settled.
            if (page.kind() == Kind.GEAR && stats != null && settled()) {
                drawCargoTooltips(g, stats, mouseX, mouseY);
            }
            // All-lives icon-row hover tooltips (same "settled" gate as the cargo row).
            if (page.kind() == Kind.LIVES && stats != null && settled()) {
                drawLivesTooltips(g, mouseX, mouseY);
            }
        }
    }

    // ---- Transition / backdrop ----

    /** True once the page is settled enough to show non-fadeable elements. */
    private boolean settled() {
        return uiAlpha >= SETTLED;
    }

    /**
     * Assign one ride photo to each page up-front, preferring photos not yet used
     * on another page (so the pages don't repeat the same shot). Re-run whenever
     * the page set changes; the gallery is frozen at death, so the result is
     * stable across page navigation. Leaves {@link #pageBackgrounds} empty when
     * the feature is off or no photos were captured this run → the solid overlay
     * is used instead.
     */
    private void assignBackgrounds() {
        if (!ClientDisplayConfig.isRideSnapshotsEnabled() || RideSnapshotGallery.isEmpty() || pages.isEmpty()) {
            pageBackgrounds = NO_BACKGROUNDS;
            return;
        }
        List<List<SnapshotTag>> chains = new ArrayList<>(pages.size());
        for (Page p : pages) chains.add(chainFor(p.kind()));
        pageBackgrounds = DeathBackgroundAssigner.assign(chains, RideSnapshotGallery.all());
        // Pre-resolve now so the first frame of each page doesn't hitch loading a disk-backed photo.
        // Only the assigned shots (≈ one per page) are touched, not the whole gallery.
        for (RideSnapshot s : pageBackgrounds) {
            if (s != null) s.texture();
        }
    }

    /** Each page's thematic fallback chain (tier 0 = preferred tag; empty = any shot). */
    private static List<SnapshotTag> chainFor(Kind kind) {
        return switch (kind) {
            case FALL     -> List.of(SnapshotTag.SCENIC);
            case DEEDS    -> List.of(SnapshotTag.COMBAT, SnapshotTag.SCENIC);
            case GEAR     -> List.of(SnapshotTag.GEAR, SnapshotTag.SCENIC);
            case LIVES    -> List.of(SnapshotTag.SOCIAL, SnapshotTag.SCENIC);
            case SURVEY, PLATFORM -> List.of();
        };
    }

    /** The photo assigned to {@code index}, or {@code null} if out of range / no photos. */
    private RideSnapshot bgFor(int index) {
        return (index >= 0 && index < pageBackgrounds.length) ? pageBackgrounds[index] : null;
    }

    /** Begin a transition toward {@code target}; the page swaps when its fade-out ends. */
    private void startTransition(int target) {
        imgFinishMs = 0L;               // cancel any in-flight fast reveal
        dipStartBlack = photoBlack;     // carry current darkness (advancing mid-rise won't snap)
        fromShot = bgFor(currentPage);
        toShot = bgFor(target);
        pendingPage = target;
        swapped = false;
        transStartMs = Util.getMillis();
    }

    /**
     * A click / Space during a transition settles the UI and page immediately and
     * fast-tracks the photo: hands the still-dark shot to a quick reveal (over
     * {@link #T_FADE}) instead of finishing the slow rise — and not a hard snap.
     */
    private void skipTransition() {
        if (transStartMs == 0L) return;
        if (!swapped && pendingPage >= 0) {
            currentPage = Math.min(pendingPage, Math.max(0, pages.size() - 1));
            rebuildWidgets();
        }
        swapped = true;
        imgFinishFrom = photoBlack;       // wherever the dip is now...
        imgFinishMs = Util.getMillis();   // ...rises the rest of the way fast
        transStartMs = 0L;
        pendingPage = -1;
        fromShot = null;
        toShot = null;
        uiAlpha = 1.0f;
        photoNew = false;                 // render falls back to the settled page's photo
    }

    /**
     * Drive the transition clock: compute {@link #uiAlpha} and the photo dip
     * ({@link #photoBlack}/{@link #photoNew}) for this frame, apply the deferred
     * page swap when the fade-out ends, and settle when complete.
     */
    private void updateTransition(long now) {
        if (imgFinishMs != 0L) {
            // Fast-track reveal after a skip: ramp the black overlay from where the
            // dip was down to nothing over T_FADE; the UI/page are already settled.
            uiBusy = false;
            uiAlpha = 1.0f;
            photoNew = false;
            float t = Math.min(1.0f, (float) (now - imgFinishMs) / (float) T_FADE);
            photoBlack = imgFinishFrom * (1.0f - smooth(t));
            if (t >= 1.0f) {
                imgFinishMs = 0L;
                photoBlack = 0.0f;
            }
            return;
        }
        if (transStartMs == 0L) {
            // Before the screen has opened (init not yet run) stay transparent so a
            // stray pre-init frame can't flash the UI in fully rendered. Once open
            // and not transitioning, the page is fully present.
            uiBusy = false;
            uiAlpha = opened ? 1.0f : 0.0f;
            photoBlack = 0.0f;
            photoNew = false;
            return;
        }
        long elapsed = Math.max(0L, now - transStartMs);
        // On a real photo switch the dip runs during the fade-in: the old photo dims
        // to black with the UI fade (T_DIP_DOWN), then the new one rises back from
        // black slowly (T_DIP_UP). With no switch (initial open / same photo) there's
        // no dip and the fade-in just tracks the UI.
        boolean switching = toShot != null && toShot != fromShot; // identity: every capture is distinct
        long total = switching
                ? (T_FADE + T_HOLD + T_DIP_DOWN + T_DIP_UP)
                : (T_FADE + T_HOLD + T_FADE);
        // Busy through fade-out + hold + fade-in; the slow image rise after that is
        // settled, so a click then advances instead of skipping.
        uiBusy = elapsed < (T_FADE + T_HOLD + T_FADE);

        // Once the fade-out has finished (UI invisible), apply the deferred page
        // switch so the new page's widgets/stats rebuild unseen during the hold.
        if (!swapped && elapsed >= T_FADE && pendingPage >= 0) {
            currentPage = Math.min(pendingPage, Math.max(0, pages.size() - 1));
            swapped = true;
            rebuildWidgets();
        }

        if (elapsed >= total) {
            // Settle on the new page. Backgrounds are assigned per page up-front and
            // stable, so there's nothing to lock here.
            transStartMs = 0L;
            pendingPage = -1;
            fromShot = null;
            toShot = null;
            uiAlpha = 1.0f;
            photoBlack = 0.0f;
            photoNew = false;
            return;
        }

        if (elapsed < T_FADE) {
            // Fade the old UI out; if the photo hadn't finished rising in yet, finish
            // it (dipStartBlack -> 0) in step with the fade-out so it's fully revealed
            // by the time the UI is gone.
            float k = (float) elapsed / (float) T_FADE;
            uiAlpha = smooth(1.0f - k);
            photoBlack = dipStartBlack * (1.0f - smooth(k));
            photoNew = false;
        } else if (elapsed < T_FADE + T_HOLD) {
            // Hold the now fully-revealed photo with no UI.
            uiAlpha = 0.0f;
            photoBlack = 0.0f;
            photoNew = false;
        } else {
            // Fade the new UI in (over T_FADE). The photo dims to black over
            // T_DIP_DOWN (with the UI fade), then rises back from black over the slow
            // T_DIP_UP; it swaps under full black at the bottom, so the cut isn't seen.
            long inMs = elapsed - T_FADE - T_HOLD;
            uiAlpha = smooth(Math.min(1.0f, (float) inMs / (float) T_FADE));
            if (!switching) {
                // No real switch (initial open / same photo) — never dip.
                photoBlack = 0.0f;
                photoNew = false;
            } else if (inMs < T_DIP_DOWN) {
                photoNew = false;                                          // old photo...
                photoBlack = smooth((float) inMs / (float) T_DIP_DOWN);    // ...dimming to black
            } else {
                photoNew = true;                                          // new photo (swapped under black)...
                long upMs = inMs - T_DIP_DOWN;
                photoBlack = smooth(1.0f - Math.min(1.0f, (float) upMs / (float) T_DIP_UP)); // ...rising from black
            }
        }
    }

    /** Smoothstep ease for 0..1. */
    private static float smooth(float x) {
        if (x <= 0.0f) return 0.0f;
        if (x >= 1.0f) return 1.0f;
        return x * x * (3.0f - 2.0f * x);
    }

    /** Scale only the alpha byte of an ARGB colour by the current {@link #uiAlpha}. */
    private int fade(int argb) {
        if (uiAlpha >= 1.0f) return argb;
        int a = Math.round(((argb >>> 24) & 0xFF) * uiAlpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ---- Page renderers. Each returns the y below its content. ----

    private int drawFall(GuiGraphics g, DeathStatsPacket s, DeathNarrative n,
                         int left, int w, int cx, int y, int mouseX, int mouseY) {
        // The fall-page title is the second-person death cause ("You fell from a high
        // place") when the server sent one, wrapped in the kicker style so a long cause
        // can't clip; otherwise the static "the fall" kicker.
        if (s != null && s.deathCause() != null && !s.deathCause().isEmpty()) {
            y = drawCentered(g, Component.literal(s.deathCause()), cx, w, y, KICKER) + 3;
        } else {
            drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_fall");
            y += 14;
        }
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
            // Full-body portrait beside the grid — drawn only once the page is settled
            // (the animated render can't take the UI's alpha tint); name labelled below.
            if (showPortrait && s.portrait() != null && settled()) {
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
        if (s == null) return y;
        boolean showItems = settled();

        // ---- Row 1: worn gear (weapon + 4 armor) + gathered cargo (chest + book + written book), one row.
        // The three cargo cells replace the old loot/books stat cells; their counts draw in the
        // native item-stack style via the renderItemDecorations text override.
        ItemStack[] gear = { s.mostUsedWeapon(), s.armorHead(), s.armorChest(), s.armorLegs(), s.armorFeet() };
        int gap = 6, cargoGap = 12;
        int equipW = gear.length * SLOT + (gear.length - 1) * gap;   // 5 worn slots
        int cargoW = 3 * SLOT + 2 * gap;                             // chest + book + written book
        int rowW = equipW + cargoGap + cargoW;
        int sx = left + (w - rowW) / 2;
        this.cargoRowY = y;
        this.cargoSx = sx;
        for (int i = 0; i < gear.length; i++) {
            int x = sx + i * (SLOT + gap);
            drawSlot(g, x, y);
            ItemStack stack = gear[i];
            if (!stack.isEmpty() && showItems) {
                g.renderItem(stack, x + 1, y + 1);
                g.renderItemDecorations(this.font, stack, x + 1, y + 1);
            }
        }
        int chestX = sx + equipW + cargoGap;
        int bookX = chestX + SLOT + gap;
        int writtenX = bookX + SLOT + gap;
        drawSlot(g, chestX, y);
        drawSlot(g, bookX, y);
        drawSlot(g, writtenX, y);
        if (showItems) {
            ItemStack chest = new ItemStack(Items.CHEST);
            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
            ItemStack written = new ItemStack(Items.WRITABLE_BOOK); // book & quill — the writing item
            g.renderItem(chest, chestX + 1, y + 1);
            g.renderItemDecorations(this.font, chest, chestX + 1, y + 1, Integer.toString(s.containersOpened()));
            g.renderItem(book, bookX + 1, y + 1);
            g.renderItemDecorations(this.font, book, bookX + 1, y + 1, Integer.toString(s.booksRead()));
            g.renderItem(written, writtenX + 1, y + 1);
            g.renderItemDecorations(this.font, written, writtenX + 1, y + 1, Integer.toString(s.booksWritten()));
        }
        this.containersRect = new Rect(chestX, y, SLOT, SLOT);
        this.booksRect = new Rect(bookX, y, SLOT, SLOT);
        this.writtenRect = new Rect(writtenX, y, SLOT, SLOT);
        y += SLOT + 12;

        // ---- Row 2: Dungeon Train advancements earned this life (scrollable) + see-all button.
        return drawAdvancements(g, s, left, w, y, showItems);
    }

    /**
     * The cargo page's second row: Dungeon Train advancements earned this life, tier-framed
     * and horizontally scrollable past 10, with a "see all" button outside the scroll viewport.
     * Resolves each earned id to its client-side {@link DisplayInfo}; ids the client can't resolve
     * (not synced / display-less) are skipped. Returns the y below the row.
     */
    private int drawAdvancements(GuiGraphics g, DeathStatsPacket s, int left, int w, int y, boolean showItems) {
        // Resolve each earned id to its client-side display (icon / title / description / type).
        List<AdvIcon> resolved = new ArrayList<>();
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            for (ResourceLocation id : s.earnedAdvancements()) {
                AdvancementHolder h = conn.getAdvancements().get(id);
                if (h == null) continue;
                Optional<DisplayInfo> disp = h.value().display();
                if (disp.isEmpty()) continue;
                DisplayInfo d = disp.get();
                resolved.add(new AdvIcon(d.getIcon(), d.getTitle(), d.getDescription(), d.getType(), null));
            }
        }
        int count = resolved.size();

        // Vanilla advancement-box sizing: a 26×26 framed box per tier, icon inset 5px (matches the L screen).
        int box = 26, advGap = 4, pitch = box + advGap;
        int padX = 7, padY = 6;              // background-panel padding around the box row
        int btnGap = 10, btnW = 26, btnH = 26;
        // Visible cell count: as many as fit beside the button + panel padding, capped at 10.
        int avail = Math.max(pitch, w - btnW - btnGap - 2 * padX);
        int maxVisible = Math.min(10, Math.max(1, (avail + advGap) / pitch));
        int visible = Math.min(count, maxVisible);
        int viewportW = visible > 0 ? visible * pitch - advGap : 0;
        int contentRowW = count > 0 ? count * pitch - advGap : 0;
        boolean scroll = contentRowW > viewportW;
        int panelW = viewportW > 0 ? viewportW + 2 * padX : 0;
        int panelH = box + 2 * padY;
        int assemblyW = (panelW > 0 ? panelW + btnGap : 0) + btnW;
        int panelX = left + (w - assemblyW) / 2, panelY = y;
        int vpX = panelX + padX, vpY = panelY + padY;

        gearAdvMaxScroll = Math.max(0, contentRowW - viewportW);
        gearAdvScroll = Math.max(0, Math.min(gearAdvMaxScroll, gearAdvScroll));

        // The Dungeon Train advancements-tab background (the 16×16 texture the menu tiles behind
        // the tree) — resolved from the DT root advancement so it follows the data, with a fallback.
        ResourceLocation advBg = ResourceLocation.withDefaultNamespace("textures/gui/advancements/backgrounds/adventure.png");
        if (conn != null) {
            AdvancementHolder root = conn.getAdvancements().get(
                    ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/root"));
            if (root != null && root.value().display().isPresent()) {
                advBg = root.value().display().get().getBackground().orElse(advBg);
            }
        }

        if (viewportW > 0) {
            advViewport = new Rect(vpX, vpY, viewportW, box);
            float alpha = Math.min(1f, uiAlpha);
            // Texture draws (background tiles + frame boxes) fade with the page via setColor + blend
            // — the vanilla LoadingOverlay pattern. We blit the 26×26 obtained-frame textures
            // directly (blitSprite ignores setColor, so it can't fade).
            RenderSystem.enableBlend();
            g.setColor(1f, 1f, 1f, alpha);
            // Background panel: tile the DT tab texture across the padded panel (clipped).
            g.enableScissor(panelX, panelY, panelX + panelW, panelY + panelH);
            for (int ty = panelY; ty < panelY + panelH; ty += 16) {
                for (int tx = panelX; tx < panelX + panelW; tx += 16) {
                    g.blit(advBg, tx, ty, 0.0f, 0.0f, 16, 16, 16, 16);
                }
            }
            g.disableScissor();
            // Advancement frame boxes (per-tier), clipped to the inner viewport and scrolled.
            g.enableScissor(vpX, vpY, vpX + viewportW, vpY + box);
            for (int i = 0; i < count; i++) {
                int cxp = vpX + i * pitch - gearAdvScroll;
                if (cxp + box <= vpX || cxp >= vpX + viewportW) continue;  // fully outside viewport
                AdvIcon a = resolved.get(i);
                g.blit(frameTexture(a.type()), cxp, vpY, 0.0f, 0.0f, box, box, box, box);
                if (showItems) {
                    g.setColor(1f, 1f, 1f, 1f);                 // item icons can't alpha-fade
                    g.renderFakeItem(a.icon(), cxp + 5, vpY + 5);
                    g.setColor(1f, 1f, 1f, alpha);
                }
                gearAdvIcons.add(new AdvIcon(a.icon(), a.title(), a.description(), a.type(), new Rect(cxp, vpY, box, box)));
            }
            g.disableScissor();
            g.setColor(1f, 1f, 1f, 1f);
            // Border around the panel — a 2px frame in the advancements palette (drawBorder fades it).
            drawBorder(g, panelX, panelY, panelW, panelH, 0xFF120D08);
            drawBorder(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2, 0xFF8A7355);
            if (scroll) {
                int trackY = panelY + panelH + 1;
                g.fill(vpX, trackY, vpX + viewportW, trackY + 2, fade(0xFF1C1D22));
                int thumbW = Math.max(12, (int) ((long) viewportW * viewportW / contentRowW));
                int thumbX = vpX + (gearAdvMaxScroll > 0 ? (viewportW - thumbW) * gearAdvScroll / gearAdvMaxScroll : 0);
                g.fill(thumbX, trackY, thumbX + thumbW, trackY + 2, fade(0xFF4A443C));
            }
        }

        // "See all advancements" button — beveled, outside the panel, vertically centred beside it.
        int btnX = panelW > 0 ? panelX + panelW + btnGap : panelX;
        int btnY = panelY + (panelH - btnH) / 2;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, fade(BTN_BG));
        g.fill(btnX, btnY, btnX + btnW, btnY + 2, fade(BTN_LIGHT));
        g.fill(btnX, btnY, btnX + 2, btnY + btnH, fade(BTN_LIGHT));
        g.fill(btnX, btnY + btnH - 2, btnX + btnW, btnY + btnH, fade(BTN_DARK));
        g.fill(btnX + btnW - 2, btnY, btnX + btnW, btnY + btnH, fade(BTN_DARK));
        if (showItems) g.renderFakeItem(new ItemStack(Items.KNOWLEDGE_BOOK), btnX + 5, btnY + 5);
        this.seeAllRect = new Rect(btnX, btnY, btnW, btnH);

        return panelY + panelH + (scroll ? 8 : 4);
    }

    /** The standalone obtained-frame texture for a tier — blit directly so it fades via setColor. */
    private static ResourceLocation frameTexture(AdvancementType t) {
        String n = switch (t) {
            case CHALLENGE -> "challenge_frame_obtained";
            case GOAL -> "goal_frame_obtained";
            default -> "task_frame_obtained";
        };
        return ResourceLocation.withDefaultNamespace("textures/gui/sprites/advancements/" + n + ".png");
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
            // Left: the six lifetime text tiles (narrowed, 3 cols × 2 rows). Right: a 3×3 hover-icon
            // grid, top-aligned with the tiles, so the icons sit beside — not below — the text.
            int igap = 4;
            int gridW = 3 * SLOT + 2 * igap;      // 3-column icon grid width
            int gridGap = 14;                     // gap between the text tiles and the icon grid
            int leftW = w - gridW - gridGap;      // region the six text tiles share
            int colW = leftW / 3;
            int cellW = colW - 8;                 // narrower than the old full-width 96
            int c0 = left + colW / 2;
            int c1 = left + colW + colW / 2;
            int c2 = left + 2 * colW + colW / 2;
            drawCell(g, c0, y, fmtAboard(s.lifeTrainTicks()), "gui.dungeontrain.death.narr.lbl_total_aboard", cellW);
            drawCell(g, c1, y, Long.toString(s.lifeCarriages()), "gui.dungeontrain.death.narr.lbl_carriages", cellW);
            drawCell(g, c2, y, fmtDist(s.lifeDistance()), "gui.dungeontrain.death.narr.lbl_distance", cellW);
            int y2 = y + 30;
            drawCell(g, c0, y2, Long.toString(s.lifeDeaths()), "gui.dungeontrain.death.narr.lbl_deaths", cellW);
            drawCell(g, c1, y2, Long.toString(s.lifeFriends()), "gui.dungeontrain.death.narr.lbl_friends", cellW);
            drawCell(g, c2, y2, Long.toString(s.lifeBooks()), "gui.dungeontrain.death.narr.lbl_books", cellW);
            // 3×3 icon grid, right-aligned to the content and top-aligned with the tiles.
            drawLifeIcons(g, s, left + w - gridW, y, igap);
            y = y2 + 30;
        }
        return y;
    }

    /** One lifetime icon-row cell: its icon (null ⇒ the heart sprite), its count string, its tooltip key. */
    private record LifeIcon(ItemStack icon, String count, String tipKey) {}

    /**
     * The all-lives icon block: nine compact hover-labelled item icons for lifetime totals, laid out
     * as a fixed 3×3 grid whose top-left is {@code (gridLeft, gridTop)}. Each cell reuses the GEAR
     * cargo-icon style (slot + item + count decoration); "hearts lost" draws the vanilla heart sprite
     * with a manual count. Captures a {@link Rect} per cell into {@link #lifeStats} for
     * {@link #drawLivesTooltips}. Fill order is row-major: cargo/health, then combat, then social/meta.
     */
    private void drawLifeIcons(GuiGraphics g, DeathStatsPacket s, int gridLeft, int gridTop, int gap) {
        boolean show = settled();
        long hearts = Math.round(s.lifeDamageTaken() / 2.0);   // damage-taken health points → hearts
        List<LifeIcon> icons = List.of(
            new LifeIcon(new ItemStack(Items.WRITABLE_BOOK), compact(s.lifeBooksWritten()),        "tip_life_written"),
            new LifeIcon(new ItemStack(Items.CHEST),         compact(s.lifeContainers()),          "tip_life_chests"),
            new LifeIcon(null,                               compact(hearts),                      "tip_life_hearts"),
            new LifeIcon(new ItemStack(Items.IRON_SWORD),    compact(Math.round(s.lifeDamageDealt())), "tip_life_damage"),
            new LifeIcon(new ItemStack(Items.ROTTEN_FLESH),  compact(s.lifeMobKills()),            "tip_life_mobs"),
            new LifeIcon(new ItemStack(Items.SKELETON_SKULL), compact(s.lifePlayersKilled()),       "tip_life_pmkills"),
            new LifeIcon(new ItemStack(Items.PLAYER_HEAD),   compact(s.lifePlayersEncountered()),  "tip_life_others"),
            new LifeIcon(new ItemStack(Items.ECHO_SHARD),    compact(s.lifeEchos()),               "tip_life_echos"),
            new LifeIcon(new ItemStack(Items.KNOWLEDGE_BOOK),compact(s.lifeAdvancements()),        "tip_life_adv")
        );
        int cols = 3;
        for (int i = 0; i < icons.size(); i++) {
            LifeIcon e = icons.get(i);
            int x = gridLeft + (i % cols) * (SLOT + gap);
            int y = gridTop + (i / cols) * (SLOT + gap);
            drawSlot(g, x, y);
            if (show) {
                if (e.icon() != null) {
                    g.renderItem(e.icon(), x + 1, y + 1);
                    g.renderItemDecorations(this.font, e.icon(), x + 1, y + 1, e.count());
                } else {
                    g.blitSprite(HEART_SPRITE, x + 1, y + 1, 16, 16);
                    int tw = this.font.width(e.count());
                    g.drawString(this.font, e.count(), x + SLOT - 1 - tw, y + SLOT - 8, 0xFFFFFF);
                }
            }
            lifeStats.add(new LifeStat(new Rect(x, y, SLOT, SLOT), "gui.dungeontrain.death.narr." + e.tipKey()));
        }
    }

    /** Compact a lifetime total to ≤4 chars so it fits the icon-count corner (9999 / 12k / 3m / 1b). */
    private static String compact(long v) {
        if (v < 10_000) return Long.toString(v);
        if (v < 1_000_000) return (v / 1000) + "k";
        if (v < 1_000_000_000) return (v / 1_000_000) + "m";
        return (v / 1_000_000_000) + "b";
    }

    /** All-lives icon-row hover tooltips — one label at a time, from the {@link #lifeStats} regions. */
    private void drawLivesTooltips(GuiGraphics g, int mouseX, int mouseY) {
        for (LifeStat st : lifeStats) {
            if (st.rect().has(mouseX, mouseY)) {
                g.renderTooltip(this.font, Component.translatable(st.tipKey()), mouseX, mouseY);
                return;
            }
        }
    }

    private int drawSurvey(GuiGraphics g, SurveyQuestionPayload.Entry e, int left, int w, int cx, int y) {
        scoreRects.clear();
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_ledger");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        // The first survey question keeps the calm "ledger" intro; the second (and any later)
        // question switches to a more urgent plea. drawSurvey only runs for the current page, so
        // the survey's ordinal is how many SURVEY pages occur up to and including this one.
        int surveyOrdinal = 0;
        for (int i = 0; i <= currentPage && i < pages.size(); i++) {
            if (pages.get(i).kind() == Kind.SURVEY) surveyOrdinal++;
        }
        String introKey;
        if (e != null && e.id().equals(BUG_REPORT_ID)) {
            // The bug question is practical, not an emotional feedback plea — use its own short intro.
            introKey = "gui.dungeontrain.death.narr.bug_intro";
        } else if (surveyOrdinal <= 1) {
            introKey = "gui.dungeontrain.death.narr.survey_intro";
        } else {
            if (surveyIntro2Choice < 0) {
                surveyIntro2Choice = ThreadLocalRandom.current().nextInt(SURVEY_INTRO_2_KEYS.length);
            }
            introKey = SURVEY_INTRO_2_KEYS[surveyIntro2Choice];
        }
        y = drawCentered(g, Component.translatable(introKey), cx, w, y, NARR);
        y += 6;
        if (e != null) {
            // Localize the prompt for display via DiscordPresence's derived survey key (falling back
            // to the literal, which still drives the Discord embed + the answer-matching logic below).
            y = drawQuestion(g, Component.translatableWithFallback(SurveyKeys.promptKey(e.id()), e.prompt()).getString(), cx, w, y);
            y += 4;
            int selected = scores.getOrDefault(e.id(), -1);
            if (!e.options().isEmpty()) {
                // Multiple-choice: labelled tiles in a single row across the content width. The chosen
                // 0-based index is stored as the score.
                List<String> options = e.options();
                int n = options.size();
                int gap = 4;
                int cellW = (w - (n - 1) * gap) / n;
                int tileH = 20;
                int sx = cx - w / 2;
                for (int i = 0; i < n; i++) {
                    int value = e.scaleMin() + i; // scaleMin is 0 for choices → value is the index
                    int tx = sx + i * (cellW + gap);
                    boolean sel = selected == value;
                    g.fill(tx, y, tx + cellW, y + tileH, fade(sel ? BTN_PRI_BG : SCORE_BG));
                    drawBorder(g, tx, y, cellW, tileH, sel ? BTN_PRI_LIGHT : SCORE_BORDER);
                    drawCenteredStr(g, Component.translatableWithFallback(SurveyKeys.optionKey(e.id(), i), options.get(i)).getString(),
                            tx + cellW / 2, y + (tileH - this.font.lineHeight) / 2 + 1,
                            sel ? 0xFFFFFFFF : SCORE_TEXT);
                    scoreRects.add(new Rect(tx, y, cellW, tileH));
                }
                y += tileH + 8;
            } else if (e.scaleMax() >= e.scaleMin()) {
                // The 0–N rating row — only for scale questions. A text question
                // (scaleMax < scaleMin) shows no tiles; its answer box is the sole input.
                int n = e.scaleMax() - e.scaleMin() + 1;
                int tile = 22, gap = 4;
                int rowW = n * tile + (n - 1) * gap;
                int sx = cx - rowW / 2;
                for (int i = 0; i < n; i++) {
                    int score = e.scaleMin() + i;
                    int tx = sx + i * (tile + gap);
                    boolean sel = selected == score;
                    g.fill(tx, y, tx + tile, y + tile, fade(sel ? BTN_PRI_BG : SCORE_BG));
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
            // Privacy notice: only when the bug question has a real-bug option selected (not "No"),
            // warning that recent logs will be attached. For "Lag" the notice also mentions the
            // system-spec summary that gets collected to diagnose performance.
            if (e.id().equals(BUG_REPORT_ID) && selected >= 0 && selected < e.options().size()
                    && !e.options().get(selected).equalsIgnoreCase("No")) {
                String noticeKey = e.options().get(selected).equalsIgnoreCase("Lag")
                        ? "gui.dungeontrain.death.narr.bug_log_notice_lag"
                        : "gui.dungeontrain.death.narr.bug_log_notice";
                y = drawCentered(g, Component.translatable(noticeKey), cx, w, y, SUBLINE);
                y += 4;
            }
        }
        return y;
    }

    private int drawPlatform(GuiGraphics g, DeathNarrative n, int left, int w, int cx, int y) {
        drawKicker(g, cx, y, "gui.dungeontrain.death.narr.kicker_platform");
        y += 14;
        drawTrain(g, left, w, y, currentPage);
        y += 46;
        // Fallback so the final page is never blank — e.g. a low-deaths Free Play
        // (creative) death where no conditioned platform lore entry matched.
        String platformNarration = n.platformNarration();
        if (n.platformQuestion().isEmpty() && platformNarration.isEmpty() && n.platformEpitaph().isEmpty()) {
            platformNarration = Component.translatable("gui.dungeontrain.death.narr.platform_fallback").getString();
        }
        y = drawQuestion(g, n.platformQuestion(), cx, w, y);
        y = drawNarration(g, platformNarration, cx, w, y);
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
        // Leave the line — smaller, secondary, beneath it. Hold Shift to convert it
        // into a "Quit Game" button (darker, lighter text + bevel) that quits to desktop.
        boolean quit = Screen.hasShiftDown();
        int lvW = 116, lvH = 15;
        platformLeaveRect = drawBevel(g, cx - lvW / 2, y, lvW, lvH,
                Component.translatable(quit ? "menu.quit" : "gui.dungeontrain.death.leave_line"),
                quit ? BTN_QUIT_BG    : BTN_BG,
                quit ? BTN_QUIT_LIGHT : BTN_LIGHT,
                quit ? BTN_QUIT_DARK  : BTN_DARK,
                quit ? BTN_QUIT_TEXT  : BTN_TEXT);
        return y + lvH + 6;
    }

    // ---- Chrome ----

    private void drawTopBar(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, Component.translatable("gui.dungeontrain.death.narr.brand"),
                12, 10, fade(0xFF7A828C), false);
        // Right-aligned: [photos] [reboard] [leave]. leave on the far right. Holding
        // Shift turns the leave chip into a "Quit Game" chip (darker, lighter border + text).
        boolean quit = Screen.hasShiftDown();
        Component leave = Component.translatable(quit ? "menu.quit" : "gui.dungeontrain.death.leave");
        Component reboard = Component.translatable("gui.dungeontrain.death.reboard");
        int leaveW = this.font.width(leave) + 16;
        int reboardW = this.font.width(reboard) + 16;
        int leaveX = this.width - 10 - leaveW;
        int reboardX = leaveX - 6 - reboardW;
        leaveRect = quit
                ? drawChip(g, leaveX, 8, leave, CHIP_LV_QUIT_BG, CHIP_LV_QUIT_BORDER, CHIP_LV_QUIT_TEXT)
                : drawChip(g, leaveX, 8, leave, CHIP_LV_BORDER, CHIP_LV_TEXT);
        reboardRect = drawChip(g, reboardX, 8, reboard, CHIP_RB_BORDER, CHIP_RB_TEXT);

        // "photos" → ride-photo gallery. Only on the final platform page, and only
        // when this run actually captured photos to browse.
        photosRect = null;
        boolean onPlatform = !pages.isEmpty() && pages.get(currentPage).kind() == Kind.PLATFORM;
        if (onPlatform && !RideSnapshotGallery.isEmpty()) {
            Component photos = Component.translatable("gui.dungeontrain.death.narr.photos", RideSnapshotGallery.size());
            int photosW = this.font.width(photos) + 16;
            int photosX = reboardX - 6 - photosW;
            photosRect = drawChip(g, photosX, 8, photos, CHIP_PH_BORDER, CHIP_PH_TEXT);
        }
    }

    /** Index of the data-driven bug-report survey page, or -1 if it isn't in this run's set. */
    private int bugPageIndex() {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.kind() == Kind.SURVEY && p.survey() != null && BUG_REPORT_ID.equals(p.survey().id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * True once the bug-report question has been submitted with a real-bug answer (anything
     * but "No"). Drives the start-page shortcut's red "Submit Bug" → green "Bug Submitted"
     * flip; a "No" answer (or a later change/clear of the answer) keeps it red.
     */
    private boolean bugReported() {
        int idx = bugPageIndex();
        if (idx < 0 || !submitted.contains(BUG_REPORT_ID)) return false;
        SurveyQuestionPayload.Entry e = pages.get(idx).survey();
        int sel = scores.getOrDefault(BUG_REPORT_ID, -1);
        if (sel < 0 || sel >= e.options().size()) return false;
        return !e.options().get(sel).equalsIgnoreCase("No");
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

            // Right of Next Screen: the bug shortcut (start page) or a per-form "Sent" badge.
            int slotX = bx + bw + 8;
            if (page.kind() == Kind.FALL && bugPageIndex() >= 0) {
                if (bugReported()) {
                    // Faded-green status — not clickable (bugReportRect stays null).
                    Component done = Component.translatable("gui.dungeontrain.death.narr.bug_submitted");
                    int dw = this.font.width(done) + 16;
                    drawBevel(g, slotX, rowY, dw, h, done, BTN_DONE_BG, BTN_DONE_LIGHT, BTN_DONE_DARK, BTN_DONE_TEXT);
                } else {
                    Component submit = Component.translatable("gui.dungeontrain.death.narr.bug_submit");
                    int sw = this.font.width(submit) + 16;
                    // Prompt above the button inviting a report (red state only).
                    drawCenteredStr(g, Component.translatable("gui.dungeontrain.death.narr.bug_prompt"),
                            slotX + sw / 2, rowY - this.font.lineHeight - 2, BTN_BUG_LIGHT);
                    bugReportRect = drawBevel(g, slotX, rowY, sw, h, submit, BTN_BUG_BG, BTN_BUG_LIGHT, BTN_DARK, BTN_BUG_TEXT);
                }
            } else if (page.kind() == Kind.SURVEY && page.survey() != null
                    && submitted.contains(page.survey().id())) {
                // Confirmation that this feedback form was sent (clears if the answer changes).
                drawChip(g, slotX, rowY + 2, Component.translatable("gui.dungeontrain.death.narr.feedback_sent"),
                        CHIP_RB_BORDER, CHIP_RB_TEXT);
            }
        }

        // Bare back-arrow (icon only), left of the button row — hidden on page 0.
        if (currentPage > 0) {
            int ax = this.width / 2 - (page.kind() == Kind.PLATFORM ? 110 : 60) - 22;
            boolean hover = mouseX >= ax - 4 && mouseX < ax + 14 && mouseY >= rowY && mouseY < rowY + 18;
            g.drawString(this.font, "←", ax, rowY + 5, fade(hover ? QUESTION : 0xFF8A909A), false);
            backRect = new Rect(ax - 4, rowY, 22, 18);
        }
    }

    // ---- Click handling ----

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // While the chrome is mid-fade, any click fast-tracks it rather than acting
        // on the fading / hidden controls beneath. Once settled (incl. the slow image
        // rise) clicks fall through, so only the Continue button advances — not empty space.
        if (button == 0 && uiBusy) { skipTransition(); return true; }
        if (button == 0) {
            if (photosRect != null && photosRect.has(mx, my)) { openGallery(); return true; }
            if (reboardRect != null && reboardRect.has(mx, my)) { boardAnew(); return true; }
            if (leaveRect != null && leaveRect.has(mx, my)) { leaveOrQuit(); return true; }
            Page page = pages.isEmpty() ? Page.of(Kind.FALL) : pages.get(currentPage);
            if (bugReportRect != null && bugReportRect.has(mx, my)) {
                // Jump straight to the bug-report question; Next Screen there returns here.
                returnToStartAfterBug = true;
                startTransition(bugPageIndex());
                return true;
            }
            if (page.kind() == Kind.PLATFORM) {
                if (boardAnewRect != null && boardAnewRect.has(mx, my)) { boardAnew(); return true; }
                if (platformLeaveRect != null && platformLeaveRect.has(mx, my)) { leaveOrQuit(); return true; }
            } else if (continueRect != null && continueRect.has(mx, my)) {
                advance();
                return true;
            }
            if (backRect != null && backRect.has(mx, my)) { back(); return true; }
            if (page.kind() == Kind.GEAR && seeAllRect != null && seeAllRect.has(mx, my)) {
                openAdvancements();
                return true;
            }
            if (page.kind() == Kind.SURVEY && page.survey() != null) {
                String qid = page.survey().id();
                for (int i = 0; i < scoreRects.size(); i++) {
                    if (scoreRects.get(i).has(mx, my)) {
                        int value = page.survey().scaleMin() + i;
                        // Changing the answer clears its "Sent" badge so it re-submits on
                        // Next Screen; re-clicking the same tile is a no-op for that state.
                        if (value != scores.getOrDefault(qid, -1)) submitted.remove(qid);
                        scores.put(qid, value);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        // Horizontal-scroll the GEAR advancements row when the cursor is over its viewport.
        if (!pages.isEmpty() && pages.get(currentPage).kind() == Kind.GEAR
                && advViewport != null && advViewport.has(mx, my) && gearAdvMaxScroll > 0) {
            int step = SLOT + 4;  // one cell per notch
            gearAdvScroll = Math.max(0, Math.min(gearAdvMaxScroll, gearAdvScroll - (int) Math.round(dy) * step));
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }


    private void advance() {
        if (pages.isEmpty() || uiBusy) return;
        Page page = pages.get(currentPage);
        if (page.kind() == Kind.SURVEY) {
            // Submit the answer if one was given; the survey is optional, so an
            // unanswered question must not block Continue.
            maybeSubmit(page.survey());
        }
        // Bug page reached via the start-page shortcut: Next Screen returns to the start
        // (page 0) instead of paging forward. Consume the flag either way.
        boolean returnToStart = returnToStartAfterBug
                && page.kind() == Kind.SURVEY && page.survey() != null
                && BUG_REPORT_ID.equals(page.survey().id());
        returnToStartAfterBug = false;
        if (returnToStart) {
            startTransition(0);
        } else if (currentPage < pages.size() - 1) {
            startTransition(currentPage + 1);
        }
    }

    private void back() {
        if (uiBusy) return;
        returnToStartAfterBug = false;
        if (currentPage > 0) {
            startTransition(currentPage - 1);
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
        // For a real-bug answer, collect + ship logs (shared with the /bug + /feedback path).
        BugLogReporter.maybeReport(e, score);
    }

    private void boardAnew() {
        DeathScreenLayoutHandler.launchWorld(this, false);
    }

    private void leave() {
        DeathScreenLayoutHandler.goToTitleScreen();
    }

    /**
     * The leave control's action, gated on Shift: with Shift held it quits Minecraft to
     * desktop ({@link DeathScreenLayoutHandler#quitToDesktop()}); otherwise it returns to
     * the title screen via {@link #leave()}. Both render and click poll
     * {@link Screen#hasShiftDown()}, so the action matches the drawn label.
     */
    private void leaveOrQuit() {
        if (Screen.hasShiftDown()) {
            DeathScreenLayoutHandler.quitToDesktop();
        } else {
            leave();
        }
    }

    private void openGallery() {
        Minecraft.getInstance().setScreen(new RideGalleryScreen(this));
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
        int faded = fade(color);
        for (FormattedCharSequence line : lines) {
            int lw = this.font.width(line);
            g.drawString(this.font, line, cx - lw / 2, y, faded, false);
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
        g.fill(x, y, x + cw, y + ch, fade(TILE_BG));
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
        g.drawString(this.font, name, -tw / 2, 0, fade(VALUE), false);
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
        g.fill(left + 2, railY, left + w - 2, railY + 2, fade(RAIL));
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
            g.fill(cxp, railY - carH, cxp + carW, railY, fade(0xFF33353E));
            g.fill(cxp, railY - carH, cxp + carW, railY - carH + 2, fade(RED));
            g.fill(cxp + 4, railY - carH + 4, cxp + 9, railY - carH + 9, fade(0xFF14151A));
            g.fill(cxp + 13, railY - carH + 4, cxp + 18, railY - carH + 9, fade(0xFF14151A));
        }
        // The fade tail trails off beyond the solid run on every screen but the
        // last, where the train has filled the rail.
        if (!lastPage) {
            int[] tail = { 0x8033353E, 0x4D2B2C33, 0x2624252B };
            int fadeX = startX + solid * spacing;
            for (int j = 0; j < tail.length; j++) {
                int cxp = fadeX + j * spacing;
                int fw = Math.min(cxp + carW, rightEdge);
                if (fw <= cxp) break;
                int fh = carH - 2 - j * 3;
                if (fh < 5) fh = 5;
                g.fill(cxp, railY - fh, fw, railY, fade(tail[j]));
            }
        }
        g.drawString(this.font, "∞", left + w - 12, railY - 8, fade(INF), false);
    }

    /** A single beveled inventory-style slot (matches the loadout slots). */
    private void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, fade(SLOT_BG));
        g.fill(x, y, x + SLOT, y + 1, fade(SLOT_DARK));
        g.fill(x, y, x + 1, y + SLOT, fade(SLOT_DARK));
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, fade(SLOT_LIGHT));
        g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, fade(SLOT_LIGHT));
    }

    /**
     * Cargo-page hover tooltips: worn-gear item tooltips, the chest/book cargo-icon labels,
     * advancement titles (within the scroll viewport), and the see-all button. Uses the row
     * geometry + clickable regions captured during {@link #drawGear}. One tooltip at a time.
     */
    private void drawCargoTooltips(GuiGraphics g, DeathStatsPacket s, int mouseX, int mouseY) {
        if (cargoRowY >= 0 && cargoSx >= 0) {
            ItemStack[] gear = { s.mostUsedWeapon(), s.armorHead(), s.armorChest(), s.armorLegs(), s.armorFeet() };
            int gap = 6;
            for (int i = 0; i < gear.length; i++) {
                int x = cargoSx + i * (SLOT + gap);
                ItemStack stack = gear[i];
                if (stack.isEmpty()) continue;
                if (mouseX >= x && mouseX < x + SLOT && mouseY >= cargoRowY && mouseY < cargoRowY + SLOT) {
                    g.renderTooltip(this.font, Screen.getTooltipFromItem(Minecraft.getInstance(), stack),
                            stack.getTooltipImage(), mouseX, mouseY);
                    return;
                }
            }
        }
        if (containersRect != null && containersRect.has(mouseX, mouseY)) {
            g.renderTooltip(this.font, Component.translatable("gui.dungeontrain.death.narr.tip_loot"), mouseX, mouseY);
            return;
        }
        if (booksRect != null && booksRect.has(mouseX, mouseY)) {
            g.renderTooltip(this.font, Component.translatable("gui.dungeontrain.death.narr.tip_books"), mouseX, mouseY);
            return;
        }
        if (writtenRect != null && writtenRect.has(mouseX, mouseY)) {
            g.renderTooltip(this.font, Component.translatable("gui.dungeontrain.death.narr.tip_written"), mouseX, mouseY);
            return;
        }
        if (advViewport != null && advViewport.has(mouseX, mouseY)) {
            for (AdvIcon a : gearAdvIcons) {
                if (a.rect().has(mouseX, mouseY)) {
                    // Same content + tier colouring as the advancements-menu hover: title, then
                    // the description tinted by the frame's chat colour.
                    List<Component> lines = new ArrayList<>();
                    lines.add(a.title());
                    if (a.description() != null && !a.description().getString().isEmpty()) {
                        lines.add(a.description().copy().withStyle(a.type().getChatColor()));
                    }
                    g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                    return;
                }
            }
        }
        if (seeAllRect != null && seeAllRect.has(mouseX, mouseY)) {
            g.renderTooltip(this.font, Component.translatable("gui.dungeontrain.death.narr.see_all_adv"), mouseX, mouseY);
        }
    }

    /** Open the vanilla advancements screen (DT tab via DefaultAdvancementsTab); returns here on close. */
    private void openAdvancements() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        mc.setScreen(new AdvancementsScreen(mc.getConnection().getAdvancements(), this));
    }

    private Rect drawChip(GuiGraphics g, int x, int y, Component text, int border, int textColor) {
        return drawChip(g, x, y, text, 0x66000000, border, textColor);
    }

    private Rect drawChip(GuiGraphics g, int x, int y, Component text, int bg, int border, int textColor) {
        int w = this.font.width(text) + 16, h = 14;
        g.fill(x, y, x + w, y + h, fade(bg));
        drawBorder(g, x, y, w, h, border);
        g.drawString(this.font, text, x + 8, y + (h - this.font.lineHeight) / 2 + 1, fade(textColor), false);
        return new Rect(x, y, w, h);
    }

    private Rect drawBevel(GuiGraphics g, int x, int y, int w, int h, Component text,
                           int bg, int light, int dark, int textColor) {
        g.fill(x, y, x + w, y + h, fade(bg));
        g.fill(x, y, x + w, y + 2, fade(light));
        g.fill(x, y, x + 2, y + h, fade(light));
        g.fill(x, y + h - 2, x + w, y + h, fade(dark));
        g.fill(x + w - 2, y, x + w, y + h, fade(dark));
        drawCenteredStr(g, text, x + w / 2, y + (h - this.font.lineHeight) / 2 + 1, textColor);
        return new Rect(x, y, w, h);
    }

    private void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c = fade(color);
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private void drawCenteredStr(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, fade(color), false);
    }

    private void drawCenteredStr(GuiGraphics g, Component c, int cx, int y, int color) {
        g.drawString(this.font, c, cx - this.font.width(c) / 2, y, fade(color), false);
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
        // Lift the gallery freeze. The world-leave paths clear the gallery anyway, but if the
        // screen is ever dismissed without disconnecting, capture/offload must resume normally.
        RideSnapshotGallery.unfreeze();
    }
}
