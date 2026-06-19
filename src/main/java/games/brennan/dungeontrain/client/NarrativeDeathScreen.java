package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.SurveyClientState;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.discordpresence.network.SurveySubmitPayload;
import games.brennan.dungeontrain.net.DeathNarrative;
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
    private static final int CHIP_PH_BORDER = 0xFF5A5236;
    private static final int CHIP_PH_TEXT   = 0xFFC9B98A;
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

    private final Map<String, Integer> scores = new HashMap<>();
    private final Map<String, String> comments = new HashMap<>();
    private final Set<String> submitted = new HashSet<>();

    private List<Page> pages = List.of();
    private int currentPage = 0;
    private int lastSurveyCount = -1;
    private EditBox commentBox;
    private boolean opened = false;

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
        // Freeze the gallery for as long as the death screen is up: no flush / eviction / texture
        // release may run while we're blitting these photos (a released texture would blank a page).
        RideSnapshotGallery.freeze();
        pages = buildPages();
        if (currentPage >= pages.size()) currentPage = pages.size() - 1;
        if (currentPage < 0) currentPage = 0;
        assignBackgrounds();
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

            // Loadout hover tooltip last, above everything — only when settled.
            if (page.kind() == Kind.GEAR && stats != null && settled()) {
                drawLoadoutTooltip(g, stats, left, contentW, mouseX, mouseY);
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
                12, 10, fade(0xFF7A828C), false);
        // Right-aligned: [photos] [reboard] [leave]. leave on the far right.
        Component leave = Component.translatable("gui.dungeontrain.death.leave");
        Component reboard = Component.translatable("gui.dungeontrain.death.reboard");
        int leaveW = this.font.width(leave) + 16;
        int reboardW = this.font.width(reboard) + 16;
        int leaveX = this.width - 10 - leaveW;
        int reboardX = leaveX - 6 - reboardW;
        leaveRect = drawChip(g, leaveX, 8, leave, CHIP_LV_BORDER, CHIP_LV_TEXT);
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
        if (pages.isEmpty() || uiBusy) return;
        Page page = pages.get(currentPage);
        if (page.kind() == Kind.SURVEY) {
            // Submit the answer if one was given; the survey is optional, so an
            // unanswered question must not block Continue.
            maybeSubmit(page.survey());
        }
        if (currentPage < pages.size() - 1) {
            startTransition(currentPage + 1);
        }
    }

    private void back() {
        if (uiBusy) return;
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
    }

    private void boardAnew() {
        DeathScreenLayoutHandler.launchWorld(this, false);
    }

    private void leave() {
        DeathScreenLayoutHandler.goToTitleScreen();
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

    private void drawLoadout(GuiGraphics g, DeathStatsPacket s, int left, int w, int y, int mouseX, int mouseY) {
        ItemStack[] gear = { s.mostUsedWeapon(), s.armorHead(), s.armorChest(), s.armorLegs(), s.armorFeet() };
        int gap = 6;
        int rowW = gear.length * SLOT + (gear.length - 1) * gap;
        int sx = left + (w - rowW) / 2;
        boolean showItems = settled();
        for (int i = 0; i < gear.length; i++) {
            int x = sx + i * (SLOT + gap);
            g.fill(x, y, x + SLOT, y + SLOT, fade(SLOT_BG));
            g.fill(x, y, x + SLOT, y + 1, fade(SLOT_DARK));
            g.fill(x, y, x + 1, y + SLOT, fade(SLOT_DARK));
            g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, fade(SLOT_LIGHT));
            g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, fade(SLOT_LIGHT));
            ItemStack stack = gear[i];
            // Item icons can't take an alpha tint; only draw them once the page is
            // settled so they don't pop at full opacity mid-fade.
            if (!stack.isEmpty() && showItems) {
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
        g.fill(x, y, x + w, y + h, fade(0x66000000));
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
