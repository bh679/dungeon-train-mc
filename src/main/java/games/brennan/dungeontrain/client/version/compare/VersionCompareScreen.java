package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private static final int TOP = 32;
    private static final int ROW_GAP = 4;
    private static final int BOTTOM_ROW_H = 20;
    private static final int BOTTOM_GAP = 4;
    private static final int MAX_W = 420;
    private static final int PANE_BG = 0x66000000;

    /** What a row stands for: the installed build, or one platform's newest listing. */
    private record Row(@Nullable Platform platform, @Nullable FullSemver version,
                       Component heading, Component detail, boolean available) {
        boolean isInstalled() { return platform == null; }
    }

    private final Screen parent;
    private final Platform launcher = Platform.current();
    private final Optional<FullSemver> installed = FullSemver.parse(VersionInfo.VERSION);

    /** Identity of the selected row across rebuilds: the platform, or {@code null} for installed. */
    @Nullable private Platform selected;
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

        int w = Math.min(this.width - MARGIN * 2, MAX_W);
        int x = (this.width - w) / 2;
        int y = TOP;
        for (Row row : rows) {
            boolean isSelected = row.platform() == selected;
            addRenderableWidget(new VersionRowButton(x, y, w, row.heading(), row.detail(),
                    !row.available(), isSelected, b -> onRowClick(row)));
            y += VersionRowButton.HEIGHT + ROW_GAP;
        }

        int bottomY = this.height - MARGIN - BOTTOM_ROW_H;
        int paneTop = y + GAP - ROW_GAP;
        int paneBottom = bottomY - GAP;
        ShaderDetailPane previous = notes;
        notes = addRenderableWidget(new ShaderDetailPane(this.font, x + 2, paneTop + 2, w - 4,
                Math.max(this.font.lineHeight, paneBottom - paneTop - 4)));
        notes.setLines(notesFor(rowFor(selected)));
        if (previous == null) {
            notes.resetScroll();
        }
        paneRect = new int[] {x, paneTop, w, paneBottom - paneTop};

        int half = (w - BOTTOM_GAP) / 2;
        addRenderableWidget(new DarkTintedButton(x, bottomY, half, BOTTOM_ROW_H,
                Component.translatable(KEY + "get", launcher.displayName()), b -> openUpdatePage(),
                0.4F, 0.6F, 1.0F));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x + half + BOTTOM_GAP, bottomY, w - half - BOTTOM_GAP, BOTTOM_ROW_H)
                .build());
    }

    /** Called on the render thread when a platform listing lands or fails. */
    void onDataChanged() {
        rebuildWidgets();
    }

    private void onRowClick(Row row) {
        if (!row.available() && !row.isInstalled()) {
            VersionCompareState.ensureFetched();
        }
        selected = row.platform();
        selectionMade = true;
        rebuildWidgets();
        if (notes != null) {
            notes.resetScroll();
        }
    }

    private void openUpdatePage() {
        UiAnalytics.click(UiAnalytics.SURFACE_TITLE_SCREEN, TARGET_GET_UPDATE);
        ConfirmLinkScreen.confirmLinkNow(this, LauncherDetector.getUpdateUrl());
    }

    @Nullable
    private Row rowFor(@Nullable Platform platform) {
        for (Row row : rows) {
            if (row.platform() == platform) {
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
        return List.copyOf(out);
    }

    private Row installedRow() {
        Component heading = Component.translatable(KEY + "installed", VersionInfo.VERSION);
        Optional<PlatformVersions> reference = referenceListing();
        Component detail;
        if (installed.isEmpty() || reference.isEmpty()) {
            detail = Component.translatable(KEY + "behind.unknown");
        } else {
            int behind = reference.get().countNewerThan(installed.get());
            boolean ahead = reference.get().latest().map(e -> installed.get().isNewerThan(e.version())).orElse(false);
            if (ahead) {
                detail = Component.translatable(KEY + "ahead");
            } else if (behind == 0) {
                detail = Component.translatable(KEY + "uptodate");
            } else if (behind >= PackVersionFetcher.MODRINTH_PAGE) {
                detail = Component.translatable(KEY + "behind.atleast", count(behind));
            } else {
                detail = Component.translatable(KEY + "behind", count(behind));
            }
        }
        return new Row(null, installed.orElse(null), heading, detail, true);
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
            return new Row(platform, null, heading, detail, false);
        }
        Component heading = Component.translatable(KEY + "platform.latest", name, latest.get().toString());
        Component detail;
        int lag = otherLatest.isPresent() ? lagBehind(platform.other(), latest.get()) : 0;
        if (lag > 0) {
            String otherName = platform.other().displayName();
            detail = Component.translatable(KEY + "platform.lag", name, count(lag), otherName);
        } else if (isLauncher) {
            detail = Component.translatable(KEY + "platform.yours");
        } else {
            detail = Component.translatable(KEY + "platform.newer", platform.other().displayName());
        }
        return new Row(platform, latest.get(), heading, detail, true);
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

    private static Component count(int n) {
        return PluralRules.clause(ClientLanguage.selected(), KEY + "count", n);
    }

    @Nullable
    private static Platform defaultSelection(List<Row> rows) {
        Row best = null;
        for (Row row : rows) {
            if (row.version() == null) continue;
            if (best == null || row.version().isNewerThan(best.version())) {
                best = row;
            }
        }
        return best == null ? null : best.platform();
    }

    // ---- notes ------------------------------------------------------------------------------

    private List<ShaderDetailPane.Line> notesFor(@Nullable Row row) {
        if (row == null || row.version() == null) {
            return List.of();
        }
        Optional<PlatformVersions> source = referenceListing();
        if (source.isEmpty()) {
            return List.of();
        }
        // The installed row, or a build ahead of the listing, shows just that version. A newer
        // release shows everything between the installed build and it, newest first.
        boolean cumulative = !row.isInstalled() && installed.isPresent()
                && row.version().isNewerThan(installed.get());
        if (cumulative) {
            List<ReleaseEntry> between = source.get().entriesBetween(installed.get(), row.version());
            if (!between.isEmpty()) {
                return ChangelogLines.forEntries(between);
            }
        }
        ReleaseEntry entry = source.get().find(row.version())
                .orElse(new ReleaseEntry(row.version(), null, ""));
        return ChangelogLines.forEntry(entry);
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
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
