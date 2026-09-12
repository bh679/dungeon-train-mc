package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import games.brennan.dungeontrain.narrative.PluralRules;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Versions page, opened from the version label in the top-left of the title and pause
 * screens. Three rows — the installed build, the newest release on the player's launcher, and
 * the newest on the other launcher when that one is ahead — with the selected row's release
 * notes in a scrolling box beneath. The newest version present is selected on open, so the page
 * answers "what did I miss" before a click.
 *
 * <p>Counting is done against Modrinth's listing, which receives every release; CurseForge only
 * gets operator releases once its review clears, so it is regularly behind, and the row says so
 * in words rather than leaving the player to compare numbers.</p>
 *
 * <p>The rows are rebuilt — not mutated — whenever the selection moves or a listing arrives,
 * which keeps every label a pure function of the data.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VersionCompareScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.version.compare.";
    private static final String TARGET_OPEN = "version_compare";
    private static final String TARGET_GET_UPDATE = "version_get_update";

    private static final int MARGIN = 16;
    private static final int GAP = 8;
    /** The wordmark at 60 % of the main menu's size — the page's title, not a competing menu. */
    private static final int LOGO_TOP = 8;
    private static final int LOGO_SRC_W = 776;
    private static final int LOGO_SRC_H = 214;
    private static final int LOGO_W = Math.round(LogoRenderer.LOGO_WIDTH * 0.6F);
    private static final int LOGO_H = Math.round((float) LOGO_W * LOGO_SRC_H / LOGO_SRC_W);
    /** The version in the main menu's splash slot, at the splash's own size. */
    private static final float SPLASH_MULTIPLIER = 1.6F;
    private static final float SPLASH_ANGLE = -20.0F;
    private static final int SPLASH_COLOUR = 0xFFFF00;
    /** Below this window height the header shrinks with the window so the rows still fit. */
    private static final int FULL_HEADER_HEIGHT = 400;
    private static final int ROW_GAP = 4;
    private static final int BOTTOM_ROW_H = 20;
    private static final int BOTTOM_GAP = 4;
    private static final int MAX_W = 420;
    private static final int FULLSCREEN_BTN = 14;
    private static final String FULLSCREEN_GLYPH = "⤢";
    /** The launcher row's share of the line it splits with the companion-mods row. */
    private static final float LAUNCHER_ROW_SHARE = 0.6F;
    private static final int PANE_BG = 0x66000000;

    private static final String KEY_INSTALLED = "installed";
    private static final String KEY_SIBLINGS = "siblings";

    /**
     * What a row stands for: the installed build, one platform's newest listing, or the companion
     * mods. {@code key} is the row's identity across rebuilds; {@code version} is null for rows that
     * are not one version (loading, unavailable, the companion summary).
     */
    private record Row(String key, @Nullable FullSemver version,
                       Component heading, Component detail, boolean available) {
        boolean isInstalled() { return KEY_INSTALLED.equals(key); }
        boolean isSiblings() { return KEY_SIBLINGS.equals(key); }
    }

    /** One companion mod's standing: what is installed and what Modrinth lists as newest. */
    private record SiblingStanding(SiblingMod mod, FullSemver installed, @Nullable PlatformVersions listing,
                                   VersionCompareState.Status status) {
        boolean behind() {
            return listing != null && listing.countNewerThan(installed) > 0;
        }
    }

    private final Screen parent;
    private final Platform launcher = Platform.current();
    private final Optional<FullSemver> installed = InstalledVersion.get();
    /** Where the rows begin once the header has taken its space; set in {@link #init()}. */
    private int top;
    private String headerVersion = "";
    private float headerScale = 1.0F;

    /** Identity of the selected row across rebuilds — a {@link Row#key()}. */
    @Nullable private String selected;
    private boolean selectionMade;

    private List<Row> rows = List.of();
    private ShaderDetailPane notes;
    /** x, y, w, h of the notes box, for the background fill. */
    private int[] paneRect = new int[4];

    public VersionCompareScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, TARGET_OPEN);
    }

    @Override
    protected void init() {
        VersionCompareState.ensureFetched();
        rows = buildRows();
        if (!selectionMade || rowFor(selected) == null) {
            selected = defaultSelection(rows);
        }

        layoutHeader();
        int w = Math.min(this.width - MARGIN * 2, MAX_W);
        int x = (this.width - w) / 2;
        // The launcher row and the companion-mods row share a line, 60:40 — both are "what your
        // launcher can update", and side by side they read as one status rather than two.
        Row siblings = rowFor(KEY_SIBLINGS);
        int y = top;
        for (Row row : rows) {
            if (row.isSiblings()) continue;
            int rowW = w;
            if (row.key().equals(launcher.name()) && siblings != null) {
                rowW = Math.round(w * LAUNCHER_ROW_SHARE) - ROW_GAP / 2;
                int sibX = x + rowW + ROW_GAP;
                addRow(siblings, sibX, y, x + w - sibX);
            }
            y += addRow(row, x, y, rowW) + ROW_GAP;
        }

        int bottomY = this.height - MARGIN - BOTTOM_ROW_H;
        y += GAP - ROW_GAP;
        Row current = rowFor(selected);
        Map<ChangelogTag, Integer> counts = tagCountsFor(current);
        if (!counts.isEmpty()) {
            TagFilterBar bar = new TagFilterBar(this.font, x, y, w, counts, VersionCompareState.tagFilter(),
                    this::onFilterChanged);
            bar.chips().forEach(this::addRenderableWidget);
            y += bar.height() + GAP;
        }
        int paneTop = y;
        int paneBottom = bottomY - GAP;
        ShaderDetailPane previous = notes;
        notes = addRenderableWidget(new ShaderDetailPane(this.font, x + 2, paneTop + 2, w - 4,
                Math.max(this.font.lineHeight, paneBottom - paneTop - 4)));
        notes.setLines(NotesSection.flatten(sectionsFor(current)));
        if (previous == null) {
            notes.resetScroll();
        }
        paneRect = new int[] {x, paneTop, w, paneBottom - paneTop};

        // Fullscreen, tucked into the notes box's top-right corner, inside the border.
        DarkTintedButton fullscreen = addRenderableWidget(new DarkTintedButton(
                x + w - FULLSCREEN_BTN - 3, paneTop + 3, FULLSCREEN_BTN, FULLSCREEN_BTN,
                Component.literal(FULLSCREEN_GLYPH), b -> openFullscreen()));
        fullscreen.setTooltip(Tooltip.create(Component.translatable(KEY + "fullscreen")));

        int half = (w - BOTTOM_GAP) / 2;
        addRenderableWidget(new DarkTintedButton(x, bottomY, half, BOTTOM_ROW_H,
                Component.translatable(KEY + "get", launcher.displayName()), b -> openUpdatePage(),
                0.4F, 0.6F, 1.0F));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x + half + BOTTOM_GAP, bottomY, w - half - BOTTOM_GAP, BOTTOM_ROW_H)
                .build());
    }

    /** Adds the row's button and returns its height — a row with no detail line is shorter. */
    private int addRow(Row row, int x, int y, int width) {
        return addRenderableWidget(new VersionRowButton(x, y, width, row.heading(), row.detail(),
                !row.available(), row.key().equals(selected), b -> onRowClick(row))).getHeight();
    }

    /** Called on the render thread when a platform listing lands or fails. */
    void onDataChanged() {
        rebuildWidgets();
    }

    private void onRowClick(Row row) {
        if (!row.available() && !row.isInstalled()) {
            VersionCompareState.ensureFetched();
        }
        selected = row.key();
        selectionMade = true;
        rebuildWidgets();
        if (notes != null) {
            notes.resetScroll();
        }
    }

    private void onFilterChanged(Set<ChangelogTag> filter) {
        VersionCompareState.setTagFilter(filter);
        rebuildWidgets();
        if (notes != null) {
            notes.resetScroll();
        }
    }

    private void openFullscreen() {
        Row row = rowFor(selected);
        if (row == null) return;
        Minecraft.getInstance().setScreen(new ChangelogFullscreenScreen(this, row.heading(),
                () -> sectionsFor(row), tagCountsFor(row)));
    }

    private void openUpdatePage() {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, TARGET_GET_UPDATE);
        ConfirmLinkScreen.confirmLinkNow(this, LauncherDetector.getUpdateUrl());
    }

    @Nullable
    private Row rowFor(@Nullable String key) {
        for (Row row : rows) {
            if (row.key().equals(key)) {
                return row;
            }
        }
        return null;
    }

    // ---- rows -------------------------------------------------------------------------------

    private List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        Optional<PlatformVersions> mine = VersionCompareState.versions(launcher);
        Optional<PlatformVersions> theirs = VersionCompareState.versions(launcher.other());
        Optional<FullSemver> mineLatest = mine.flatMap(PlatformVersions::latest).map(ReleaseEntry::version);
        Optional<FullSemver> theirsLatest = theirs.flatMap(PlatformVersions::latest).map(ReleaseEntry::version);

        out.add(installedRow());
        out.add(platformRow(launcher, mineLatest, theirsLatest, true));
        // The other launcher earns a row only when it has something the player's launcher does
        // not — or when the player's launcher could not be read and the other one could.
        boolean otherAhead = theirsLatest.isPresent()
                && (mineLatest.isEmpty() || theirsLatest.get().isNewerThan(mineLatest.get()));
        if (otherAhead) {
            out.add(platformRow(launcher.other(), theirsLatest, mineLatest, false));
        }
        List<SiblingStanding> siblings = siblingStandings();
        if (!siblings.isEmpty()) {
            out.add(siblingsRow(siblings));
        }
        return List.copyOf(out);
    }

    private Row installedRow() {
        // One line: "You are on v0.800.0 — 25 versions behind". The standing is a clause after the
        // dash, so the row stays a single sentence in every state.
        Optional<PlatformVersions> reference = referenceListing();
        Component standing;
        if (installed.isEmpty() || reference.isEmpty()) {
            standing = Component.translatable(KEY + "behind.unknown");
        } else {
            Optional<FullSemver> latest = reference.get().latest().map(ReleaseEntry::version);
            int releases = reference.get().countNewerThan(installed.get());
            boolean ahead = latest.map(installed.get()::isNewerThan).orElse(false);
            if (ahead) {
                standing = Component.translatable(KEY + "ahead");
            } else if (releases == 0) {
                standing = Component.translatable(KEY + "uptodate");
            } else {
                Gap gap = gap(installed.get(), latest.get(), releases);
                standing = Component.translatable(KEY + "behind", gap.versions(), gap.releases());
            }
        }
        Component heading = Component.translatable(KEY + "installed", InstalledVersion.display(), standing);
        return new Row(KEY_INSTALLED, installed.orElse(null), heading, Component.empty(), true);
    }

    private Row platformRow(Platform platform, Optional<FullSemver> latest, Optional<FullSemver> otherLatest,
                            boolean isLauncher) {
        String name = platform.displayName();
        VersionCompareState.Status status = VersionCompareState.status(platform);
        if (latest.isEmpty()) {
            Component heading = Component.translatable(KEY + "platform.latest.unknown", name);
            Component detail = status == VersionCompareState.Status.ERROR
                    ? Component.translatable(KEY + "platform.unavailable")
                    : Component.translatable(KEY + "platform.loading");
            return new Row(platform.name(), null, heading, detail, false);
        }
        Component heading = Component.translatable(KEY + "platform.latest", name, latest.get().toString());
        Component detail;
        int lag = otherLatest.isPresent() ? lagBehind(platform.other(), latest.get()) : 0;
        if (lag > 0) {
            String otherName = platform.other().displayName();
            Gap gap = gap(latest.get(), otherLatest.get(), lag);
            detail = Component.translatable(KEY + "platform.lag", name, gap.versions(), otherName, gap.releases());
        } else if (isLauncher) {
            detail = Component.translatable(KEY + "platform.yours");
        } else {
            detail = Component.translatable(KEY + "platform.newer", platform.other().displayName());
        }
        return new Row(platform.name(), latest.get(), heading, detail, true);
    }

    // ---- companion mods -----------------------------------------------------------------------

    /** Every sibling on this client, in roster order. Absent siblings have nothing to say. */
    private static List<SiblingStanding> siblingStandings() {
        List<SiblingStanding> out = new ArrayList<>();
        for (SiblingMod mod : SiblingMod.values()) {
            Optional<FullSemver> installed = mod.installedVersion();
            if (installed.isEmpty()) continue;
            out.add(new SiblingStanding(mod, installed.get(),
                    VersionCompareState.siblingVersions(mod).orElse(null),
                    VersionCompareState.siblingStatus(mod)));
        }
        return out;
    }

    private Row siblingsRow(List<SiblingStanding> siblings) {
        Component heading = Component.translatable(KEY + "siblings");
        long behind = siblings.stream().filter(SiblingStanding::behind).count();
        boolean anyLoading = siblings.stream().anyMatch(s -> s.status() == VersionCompareState.Status.LOADING);
        boolean anyFailed = siblings.stream().anyMatch(s -> s.status() == VersionCompareState.Status.ERROR);
        Component detail;
        boolean available = true;
        if (behind > 0) {
            detail = PluralRules.clause(ClientLanguage.selected(), KEY + "siblings.updates", behind);
        } else if (anyLoading) {
            detail = Component.translatable(KEY + "platform.loading");
            available = false;
        } else if (anyFailed) {
            detail = Component.translatable(KEY + "platform.unavailable");
            available = false;
        } else {
            detail = Component.translatable(KEY + "siblings.uptodate", siblings.size());
        }
        return new Row(KEY_SIBLINGS, null, heading, detail, available);
    }

    /** One section per companion: its standing, then for a lagging one the notes it is missing. */
    private static List<NotesSection> siblingSections() {
        List<NotesSection> out = new ArrayList<>();
        for (SiblingStanding s : siblingStandings()) {
            String name = s.mod().displayName();
            String have = s.installed().toString();
            List<ShaderDetailPane.Line> lines = new ArrayList<>();
            if (s.listing() == null) {
                Component text = Component.translatable(KEY + "siblings.mod.unknown", name, have,
                        Component.translatable(s.status() == VersionCompareState.Status.ERROR
                                ? KEY + "platform.unavailable" : KEY + "platform.loading"));
                lines.add(new ShaderDetailPane.Line(text, ChangelogLines.COLOUR_MUTED));
            } else {
                Optional<FullSemver> latest = s.listing().latest().map(ReleaseEntry::version);
                if (!s.behind() || latest.isEmpty()) {
                    lines.add(new ShaderDetailPane.Line(
                            Component.translatable(KEY + "siblings.mod.uptodate", name, have), ChangelogLines.COLOUR_BULLET));
                } else {
                    lines.add(ChangelogLines.heading(Component.translatable(KEY + "siblings.mod.update", name, have,
                            latest.get().toString())));
                    lines.addAll(ChangelogLines.forEntries(s.listing().entriesBetween(s.installed(), latest.get())));
                }
            }
            out.add(new NotesSection(Component.literal(name), lines));
        }
        return out;
    }

    /** How many of {@code counter}'s listed versions are newer than {@code version}. */
    private static int lagBehind(Platform counter, FullSemver version) {
        return VersionCompareState.versions(counter).map(v -> v.countNewerThan(version)).orElse(0);
    }

    /** Modrinth lists every release, so it is the yardstick; CurseForge stands in only if it is all we have. */
    private static Optional<PlatformVersions> referenceListing() {
        Optional<PlatformVersions> modrinth = VersionCompareState.versions(Platform.MODRINTH);
        return modrinth.isPresent() ? modrinth : VersionCompareState.versions(Platform.CURSEFORGE);
    }

    /**
     * "54 versions behind (25 releases)": the headline number is the MINOR-version gap — the
     * number players see in version strings — and the release count is the number of listed
     * uploads between the two, which is smaller because releases skip numbers. Across a MAJOR
     * bump the minor gap means nothing, so the release count stands alone. The release count is
     * capped by the listing page; past the cap it reads "200+ releases".
     */
    private record Gap(Component versions, Component releases) {}

    private static Gap gap(FullSemver from, FullSemver to, int releases) {
        String locale = ClientLanguage.selected();
        boolean capped = releases >= PackVersionFetcher.MODRINTH_PAGE;
        Component releasesClause = capped
                ? Component.translatable(KEY + "count.releases.capped", releases)
                : PluralRules.clause(locale, KEY + "count.releases", releases);
        if (from.major() != to.major()) {
            return new Gap(releasesClause, releasesClause);
        }
        int minorGap = to.minor() - from.minor();
        return new Gap(PluralRules.clause(locale, KEY + "count", minorGap), releasesClause);
    }

    @Nullable
    private static String defaultSelection(List<Row> rows) {
        Row best = null;
        for (Row row : rows) {
            if (row.version() == null) continue;
            if (best == null || row.version().isNewerThan(best.version())) {
                best = row;
            }
        }
        return best == null ? null : best.key();
    }

    // ---- notes ------------------------------------------------------------------------------

    /** The selected row's notes, one section per version (or per companion mod), tag-filtered. */
    private List<NotesSection> sectionsFor(@Nullable Row row) {
        if (row != null && row.isSiblings()) {
            return siblingSections();
        }
        return NotesBuilder.sections(releasesFor(row), VersionCompareState.ledger().orElse(null),
                VersionCompareState.tagFilter());
    }

    /** Entry counts per tag across the row's releases; empty for the companion row or without the ledger. */
    private Map<ChangelogTag, Integer> tagCountsFor(@Nullable Row row) {
        if (row == null || row.isSiblings()) {
            return Map.of();
        }
        return NotesBuilder.tagCounts(releasesFor(row), VersionCompareState.ledger().orElse(null));
    }

    /**
     * The releases a row's notes cover. The installed row, or a build ahead of the listing, is just
     * that version. A newer release is everything between the installed build and it, newest first.
     */
    private List<ReleaseEntry> releasesFor(@Nullable Row row) {
        if (row == null || row.version() == null) {
            return List.of();
        }
        Optional<PlatformVersions> source = referenceListing();
        if (source.isEmpty()) {
            return List.of();
        }
        boolean cumulative = !row.isInstalled() && installed.isPresent()
                && row.version().isNewerThan(installed.get());
        if (cumulative) {
            List<ReleaseEntry> between = source.get().entriesBetween(installed.get(), row.version());
            if (!between.isEmpty()) {
                return between;
            }
        }
        return List.of(source.get().find(row.version())
                .orElse(new ReleaseEntry(row.version(), null, "")));
    }

    // ---- render -----------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        // Under the widgets, so the notes pane has its box before its text is drawn.
        g.fill(paneRect[0], paneRect[1], paneRect[0] + paneRect[2], paneRect[1] + paneRect[3], PANE_BG);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderLogo(g);
        renderVersionSplash(g);
    }

    // ---- header -----------------------------------------------------------------------------

    /**
     * Sizes the header and decides where the rows start. The splash text is the newest version
     * on the page (the one the default selection lands on) — or the installed one when nothing
     * newer is listed, so the header never reads as empty. Its scale is the main menu's splash
     * formula (times {@link #SPLASH_MULTIPLIER}, kept as the one knob for the size), capped to the
     * window so a short window still shows the rows underneath.
     */
    private void layoutHeader() {
        headerVersion = "v" + newestVersion().map(FullSemver::toString).orElse(InstalledVersion.display());
        float shrink = Mth.clamp((float) this.height / FULL_HEADER_HEIGHT, 0.5F, 1.0F);
        float vanilla = 1.8F * 100.0F / (this.font.width(headerVersion) + 32);
        float wanted = vanilla * SPLASH_MULTIPLIER * shrink;
        float widthCap = this.width * 0.45F / this.font.width(headerVersion);
        headerScale = Math.min(wanted, widthCap);
        top = Math.max(LOGO_TOP + LOGO_H, splashAnchorY() + splashHalfHeight()) + GAP;
    }

    private Optional<FullSemver> newestVersion() {
        return rows.stream().map(Row::version).filter(v -> v != null).max(FullSemver::compareTo);
    }

    private int logoX() { return (this.width - LOGO_W) / 2; }

    /**
     * Splash centre: off the logo's right edge, level with its upper half. Higher and further
     * right than the main menu's so the rows can start straight under the logo.
     */
    private int splashAnchorX() { return logoX() + LOGO_W + Math.round(this.font.width(headerVersion) * headerScale * 0.45F); }
    private int splashAnchorY() { return LOGO_TOP + Math.round(LOGO_H * 0.4F); }

    /** Half the vertical extent of the rotated text, so the rows can start clear of it. */
    private int splashHalfHeight() {
        float w = this.font.width(headerVersion) * headerScale;
        float h = this.font.lineHeight * headerScale;
        double a = Math.toRadians(-SPLASH_ANGLE);
        return Mth.ceil((w * Math.sin(a) + h * Math.cos(a)) / 2.0);
    }

    private void renderLogo(GuiGraphics g) {
        RenderSystem.enableBlend();
        g.blit(LogoRenderer.MINECRAFT_LOGO, logoX(), LOGO_TOP, LOGO_W, LOGO_H,
                0.0F, 0.0F, LOGO_SRC_W, LOGO_SRC_H, LOGO_SRC_W, LOGO_SRC_H);
    }

    /** Vanilla's splash draw — same slot, same tilt, same breathing pulse — at the page's scale. */
    private void renderVersionSplash(GuiGraphics g) {
        g.pose().pushPose();
        g.pose().translate(splashAnchorX(), splashAnchorY(), 0.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees(SPLASH_ANGLE));
        float pulse = 1.0F - Mth.abs(Mth.sin((float) (Util.getMillis() % 1000L) / 1000.0F * (float) (Math.PI * 2))) * 0.055F;
        float scale = headerScale * pulse;
        g.pose().scale(scale, scale, scale);
        g.drawCenteredString(this.font, headerVersion, 0, -this.font.lineHeight / 2, SPLASH_COLOUR | 0xFF000000);
        g.pose().popPose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
