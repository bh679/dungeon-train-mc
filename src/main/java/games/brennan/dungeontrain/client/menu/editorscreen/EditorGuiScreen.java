package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.client.builder.BuilderProfileScreen;
import games.brennan.dungeontrain.client.builder.BuilderProfileStageConflictScreen;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.builder.BuilderTilePreviews;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.CommandMenuKeyBindings;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.CreatorParentPickerScreen;
import games.brennan.dungeontrain.client.menu.EditorSaveStatus;
import games.brennan.dungeontrain.client.menu.HotbarPassthrough;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.BuilderProfileDownloadPacket;
import games.brennan.dungeontrain.net.BuilderProfileActionPacket;
import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
import games.brennan.dungeontrain.net.BuilderProfileRequestPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.editor.PlotCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The inventory-style editor X menu.
 *
 * <p>Opened by {@link games.brennan.dungeontrain.client.menu.CommandMenuState#open()} in place of
 * the row-list panel anywhere in the editor world — and in an editor plot elsewhere — with the X
 * menu set to screen space. Between plots nothing is "here", so no template is selected on the way
 * in; everything else reads the same. The world keeps running underneath and the hotbar keeps
 * working — the same contract as the panel it replaces.</p>
 */
public final class EditorGuiScreen extends Screen {

    static final int SCREEN_DIM = 0x80101010;
    static final int BAKES_PER_FRAME = 2;
    static final long DOUBLE_CLICK_MS = 300;
    static final float MAX_FRAME_SECONDS = 0.1F;
    static final int REFRESH_DELAY_TICKS = 10;
    /**
     * How long a walk is given before the button comes back.
     *
     * <p>Generous, because a walk can be a category switch, which clears and restamps every plot in
     * the editor. Running out is not a failure to report — it is the screen admitting it cannot
     * tell whether the player got there, and handing the button back so they can try again.</p>
     */
    /** Long enough for the server to have asked the relay and been answered. */
    private static final int SUBMIT_REFRESH_TICKS = 20;
    static final int GOING_TIMEOUT_TICKS = 200;

    private final EditorFilterBar filterBar = new EditorFilterBar();
    private final EditorBrowserPane browser = new EditorBrowserPane();
    private final EditorDetailPane detail = new EditorDetailPane();
    private final EditorCreatorPane creatorPane = new EditorCreatorPane();
    private final EditorSettingsPane settingsPane = new EditorSettingsPane();
    private final EditorLayoutPane layoutPane = new EditorLayoutPane(this::selectOrEnter);
    private final OrbitState orbit = new OrbitState();
    private final InlineEdit inlineEdit = new InlineEdit();
    private final EditorModalHost modal = new EditorModalHost(this::onClose, this::afterCommand);
    private final EditorCreatorSearch search = new EditorCreatorSearch();

    private EditBox filterBox;
    private InventoryEditorLayout layout;
    private List<EditorTabBar.Tab> tabs = List.of();
    private EditorTabBar.Tab hoveredTab;
    private long lastFrameNanos;
    private VariantKey lastClickKey;
    private long lastClickMillis;
    private VariantKey previewKey;
    private int refreshTicks;
    /** What the last press of Load came back with, already worded for the player. */
    private String creatorNote;
    /** Whether the next press should bring the build down under a name this install is not using. */
    private boolean loadAsCopy;
    private List<String> takenNames = List.of();
    /** The build being walked to, while the walk is under way. */
    private EditorCreatorBuilds.Landed goingTo;
    private int goingTicks;
    /**
     * Ticks until the listing is asked for again after a submit or a withdraw.
     *
     * <p>Not immediate: the press goes to the server, which asks the relay, which answers — a re-read
     * fired in the same frame comes back with the row exactly as it was and reads as the button
     * having done nothing.</p>
     */
    private int submitTicks;
    /** Which version of the selected build the preview shows: 0 is the build as it is now. */
    private int previewSeq;
    /** The relay row the preview is paging, so a change of selection can reset the paging. */
    private int previewRelayId;

    public EditorGuiScreen() {
        super(Component.literal("Dungeon Train Editor"));
    }

    /** Ask for what the screen needs and show it. */
    public static void open() {
        EditorRosterClient.request();
        // Opening it inside a plot is nearly always a question about that plot — see
        // EditorScreenState.requestStandingSelection.
        EditorScreenState.requestStandingSelection();
        EditorSaveStatus.request();
        Minecraft.getInstance().setScreen(new EditorGuiScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        layout = InventoryEditorLayout.of(this.width, this.height, EditorScreenState.filtersExpanded());
        InventoryEditorLayout.Rect f = layout.filter();
        filterBar.layout(layout, this.font, EditorRosterClient.index(), EditorScreenState.page().isBrowser());
        filterBox = new EditBox(this.font, filterBar.boxX(), f.y(), filterBar.boxW(), f.h(),
            Component.literal("filter"));
        filterBox.setBordered(false);
        filterBox.setMaxLength(32);
        placeFilterBox();
        filterBox.setValue(EditorScreenState.text());
        filterBox.setResponder(text -> {
            EditorScreenState.setText(text);
            browser.resetScroll();
            layoutPane.resetScroll();
        });
        // Added as a plain child rather than a renderable: this screen draws it itself, inside a
        // scissor, so neither the typed value nor the hint can escape the box.
        addWidget(filterBox);
        filterBox.visible = hasFilterBar();
        EditorCreatorBuilds.attach();
        // The toolbar's Submit icon says whether the selected build is on the train, which only the
        // player's own listing knows. Asked on the way in so the icon is right before it is pressed.
        DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket());
        BuilderProfileState.listenForDownloads(this::onDownloadResult);
    }

    @Override
    public void removed() {
        super.removed();
        BuilderTilePreviews.clear();
        RelayBuildPreviews.clear();
        search.close();
        EditorCreatorBuilds.detach();
        BuilderProfileState.listenForDownloads(null);
    }

    /** The two tabs that browse the roster and so carry the filter bar; Settings does not. */
    private static boolean hasFilterBar() {
        return EditorScreenState.page() != EditorScreenPage.SETTINGS;
    }

    /**
     * Put the box where the bar left room for it this frame.
     *
     * <p>The hint is only offered when it fits. An EditBox draws its hint unclipped, and this box
     * is as narrow as the chips leave it, so a hint too long for it would run straight across
     * them — the magnifier beside the box already says what it is for.</p>
     */
    private void placeFilterBox() {
        if (filterBox == null) return;
        filterBox.setX(filterBar.boxX());
        filterBox.setWidth(filterBar.boxW());
        Component hint = Component.translatable(EditorScreenLang.FILTER_HINT);
        filterBox.setHint(this.font.width(hint) <= filterBar.boxW() - 2 ? hint : Component.empty());
    }

    @Override
    public void tick() {
        super.tick();
        search.tick();
        tickWalk();
        if (refreshTicks > 0 && --refreshTicks == 0) {
            EditorSaveStatus.request();
        }
        if (submitTicks > 0 && --submitTicks == 0) {
            EditorCreatorBuilds.refresh();
        }
    }

    /**
     * Watch a walk that is under way: close the screen the moment the player is standing in the
     * build, and hand the button back if they never arrive.
     *
     * <p>Closing is the point of pressing Go here — the build is a plot in the world and the menu is
     * in front of it. Watching where the player actually is, rather than closing on the press,
     * means the menu stays up when the walk did not happen (no permission, a plot that would not
     * stamp) instead of leaving them looking at the wrong place with nothing said.</p>
     */
    private void tickWalk() {
        if (goingTo == null) return;
        if (EditorTemplateJumpBridge.arrived(goingTo.kind(), goingTo.id(), goingTo.subKind())) {
            goingTo = null;
            this.onClose();
            return;
        }
        if (--goingTicks <= 0) goingTo = null;
    }

    /** A command went out: give the server a moment, then ask what changed. */
    private void afterCommand() {
        EditorRosterClient.scheduleRefresh(REFRESH_DELAY_TICKS);
        refreshTicks = REFRESH_DELAY_TICKS;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** A flat dim only — the author needs to see the plot behind the panel. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, SCREEN_DIM);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        EditorScreenTheme theme = ClientDisplayConfig.getEditorScreenTheme();
        EditorRosterIndex index = EditorRosterClient.index();
        EditorScreenState.reconcile(index);
        layout = InventoryEditorLayout.of(this.width, this.height, EditorScreenState.filtersExpanded());
        float seconds = frameSeconds();
        BuilderTilePreviews.beginFrame(BAKES_PER_FRAME);
        RelayBuildPreviews.beginFrame();

        EditorScreenActions.Ctx ctx = context(index);
        if (previewKey == null ? ctx.selection() != null : !previewKey.equals(ctx.selection())) {
            previewKey = ctx.selection();
            orbit.reset();
        }
        orbit.advance(seconds);

        boolean browsing = EditorScreenState.page().isBrowser();
        boolean filtering = hasFilterBar();
        filterBox.visible = filtering;
        filterBox.setEditable(filtering);
        if (filtering) {
            filterBar.layout(layout, this.font, index, browsing);
            placeFilterBox();
        }

        super.render(g, mouseX, mouseY, partialTick);   // background + the filter box

        drawPanel(g, theme);
        tabs = EditorTabBar.layout(layout.tabs(), this.font::width, p -> EditorScreenLang.text(p.langKey()));
        hoveredTab = modal.isOpen() || search.isOpen() ? null
            : EditorTabBar.hit(tabs, layout.tabs(), mouseX, mouseY);
        // The standing plot's category is a cell of the Templates tab now, so that is the tab that
        // wears the dot; the browser pane marks the cell itself.
        EditorTabBar.draw(g, this.font, layout.tabs(), tabs, EditorScreenState.page(),
            ctx.standing() == null ? null : EditorScreenPage.TEMPLATES,
            ctx.dirty(), hoveredTab, theme);

        boolean covered = modal.isOpen() || search.isOpen();
        int mx = covered ? -1 : mouseX;
        int my = covered ? -1 : mouseY;
        if (filtering) {
            filterBar.render(g, this.font, mx, my);
            drawFilterBox(g, mouseX, mouseY, partialTick);
        }
        if (browsing) {
            browser.layout(layout, this.font, index);
            browser.render(g, this.font, theme, seconds, mx, my);
        } else if (EditorScreenState.page() == EditorScreenPage.SETTINGS) {
            settingsPane.render(g, this.font, theme, layout, mx, my);
        } else if (EditorScreenState.page() == EditorScreenPage.LAYOUT) {
            layoutPane.render(g, this.font, theme, layout, index, ctx.selection(), ctx.standing(), mx, my);
        }

        if (EditorCreatorBuilds.active()) {
            // A relay row is not a template: none of the detail pane's controls apply to one, so
            // the pane that has no controls stands in for it rather than eight disabled buttons.
            BuilderProfilePacket.Entry picked = selectedCreatorBuild();
            trackVersionsOf(picked == null ? 0 : picked.relayId(),
                EditorCreatorBuilds.ownerOf(picked));
            creatorPane.render(g, this.font, layout, theme, picked, orbit.yaw(),
                creatorNote, loadAsCopy, EditorCreatorBuilds.here(index, picked), goingTo != null,
                previewSeq, mx, my);
        } else {
            EditorRosterIndex.Tile tile = ctx.hasSelection() ? index.find(ctx.selection()) : null;
            TemplateArt art = TemplateArt.of(ctx.selection());
            TemplateSummary summary = art == null ? null : art.summary();
            trackVersionsOf(tile == null ? 0 : tile.relayId(), "");
            detail.showVersion(tile == null ? 0 : tile.relayId(), previewSeq);
            detail.layout(layout, ctx, System.currentTimeMillis());
            detail.render(g, this.font, theme, art, summary, tile,
                EditorDetailPane.pathLabel(index, ctx.selection()), orbit.yaw(), mx, my);
        }

        String empty = browsing ? emptyGridNote(index) : null;
        if (empty != null) {
            g.drawString(this.font, empty, layout.grid().x() + 4, layout.grid().y() + 4, 0xFFFFFFFF, true);
        }

        inlineEdit.render(g, this.font);
        search.render(g, this.font, layout, theme, mouseX, mouseY);
        modal.render(g, this.font, this.width, this.height, mouseX, mouseY);
        drawTooltips(g, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphics g, EditorScreenTheme theme) {
        InventoryEditorLayout.Rect p = layout.panel();
        g.fill(p.x() - 1, p.y() - 1, p.right() + 1, p.bottom() + 1, theme.outline());
        g.fill(p.x(), p.y(), p.right(), p.bottom(), theme.panel());
        g.fill(p.x(), p.y(), p.right(), p.y() + 2, theme.bevelLight());
        g.fill(p.x(), p.y(), p.x() + 2, p.bottom(), theme.bevelLight());
        g.fill(p.x(), p.bottom() - 2, p.right(), p.bottom(), theme.bevelDark());
        g.fill(p.right() - 2, p.y(), p.right(), p.bottom(), theme.bevelDark());
        // Dark sub-panels behind the grid and the right pane, so rows read as they do everywhere.
        InventoryEditorLayout.Rect grid = layout.grid();
        g.fill(grid.x() - 1, grid.y() - 1, grid.right() + 1, grid.bottom() + 1, theme.subPanel());
        InventoryEditorLayout.Rect h = layout.header();
        InventoryEditorLayout.Rect t = layout.test();
        g.fill(h.x() - 1, h.y() - 1, h.right() + 1, t.bottom() + 1, theme.subPanel());
    }

    /**
     * The filter box, drawn inside its own rectangle and nowhere else.
     *
     * <p>An {@code EditBox} draws its value and its hint without clipping either, and this one is
     * only as wide as the filter chips leave it. Scissored, the text simply disappears where the
     * chips begin instead of running across them. The dark fill under it is what makes a
     * borderless field read as a field on the light panel.</p>
     */
    private void drawFilterBox(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (filterBox == null || !filterBox.visible) return;
        int x = filterBox.getX();
        int y = filterBox.getY();
        int w = filterBox.getWidth();
        int h = filterBox.getHeight();
        g.fill(x - 1, y, x + w + 1, y + h, MenuRowPainter.CELL_IDLE);
        g.enableScissor(x, y, x + w, y + h);
        filterBox.render(g, mouseX, mouseY, partialTick);
        g.disableScissor();
    }

    /** Why the grid is empty, or null when it is not: six answers that mean different things. */
    private String emptyGridNote(EditorRosterIndex index) {
        if (EditorCreatorBuilds.active()) {
            if (!browser.creatorTiles().isEmpty()) return null;
            BuilderProfilePacket.Status status = EditorCreatorBuilds.status();
            if (status == null) return EditorScreenLang.text(EditorScreenLang.CREATOR_LOADING);
            if (status != BuilderProfilePacket.Status.OK) {
                return EditorScreenLang.text(EditorScreenLang.CREATOR_UNAVAILABLE);
            }
            // A listing that came up dry needs a different chip; one that is genuinely bare needs a
            // different builder. Saying "nothing uploaded" of a filtered list is simply untrue.
            return EditorScreenLang.text(EditorCreatorBuilds.loadedCount() > 0
                ? EditorScreenLang.CREATOR_NO_MATCHES : EditorScreenLang.CREATOR_EMPTY);
        }
        return index.isEmpty() ? EditorScreenLang.text(EditorScreenLang.NO_ROSTER) : null;
    }

    /**
     * Bring the selected relay build down into this install's library.
     *
     * <p>The same download My Builds runs, and the reason a preview is not the end of the story: a
     * mesh can be turned around in the pane, but only a template on disk can be stood in, tested and
     * ridden. Once it lands the roster picks it up like any other template.</p>
     *
     * <p>A first press asks for the build under its own name. If that name is already spoken for
     * here, the press after it loads a copy instead — the build is somebody else's work and this
     * screen will not write over whatever is already wearing the name.</p>
     */
    private void loadSelectedCreatorBuild() {
        loadSelectedCreatorBuild(null);
    }

    /**
     * As above, carrying the player's answer to the Stage question ({@code stageOverwrite} — the ids
     * to take over the local copies; non-null means the question was put). The build, its owner
     * and its parent are read again from the selection, which the conflict screen leaves in place.
     */
    private void loadSelectedCreatorBuild(List<String> stageOverwrite) {
        BuilderProfilePacket.Entry entry = selectedCreatorBuild();
        if (entry == null) return;
        boolean live = BuilderProfileState.live();
        // The row's own owner, not the builder being viewed: the pooled listing spans them.
        String owner = EditorCreatorBuilds.ownerOf(entry);
        // The name this screen has been showing over their builds, so the install can caption the
        // template with whose work it is — the fetch itself never answers that. In the pooled
        // listing the viewed name is nobody's, so the row's own is what carries it.
        String ownerName = entry.ownerName() == null || entry.ownerName().isEmpty()
            ? EditorCreatorBuilds.viewedName()
            : entry.ownerName();
        // Where it lands in the roster: under the chosen variant parent for the kinds that have
        // sub-variants, at top level (blank) for the rest — carriages have no parents to land under.
        String parent = CreatorLoadParent.supports(entry.kind())
            ? CreatorLoadParent.parentFor(EditorCreatorBuilds.categoryOf(entry.kind())) : "";
        BuilderProfileDownloadPacket packet = loadAsCopy
            ? new BuilderProfileDownloadPacket(entry.relayId(), BuilderRelayInstall.Resolution.LOAD_AS_NEW,
                BuilderNewOptions.firstFreeName(entry.buildName(), takenNames), owner, ownerName, live, false,
                parent)
            : new BuilderProfileDownloadPacket(entry.relayId(), BuilderRelayInstall.Resolution.AS_IS, "",
                owner, ownerName, live, false, parent);
        DungeonTrainNet.sendToServer(stageOverwrite == null ? packet : packet.withStages(stageOverwrite));
        creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_LOADING_BUILD);
    }

    /**
     * Put the selected build on the train, or take it back off.
     *
     * <p>The same packet My Builds sends, and authorised the same way — by the owner secret in this
     * world's saved data, looked up server-side from the id. Nothing is assumed to have worked: the
     * server re-reads the profile once the relay answers, and the label follows what comes back
     * rather than what was asked for, because the relay can refuse (a build another world is holding).</p>
     */
    private void submitSelectedCreatorBuild() {
        BuilderProfilePacket.Entry entry = selectedCreatorBuild();
        if (entry == null || !BuilderRelayKinds.canSubmitForReview(entry.kind())) return;
        DungeonTrainNet.sendToServer(new BuilderProfileActionPacket(entry.relayId(), !entry.published()));
        // The server's own re-read is addressed to the player's OWN profile, so this listing has to
        // ask again itself — and after a moment, once the relay has actually answered the action.
        creatorNote = EditorScreenLang.text(EditorScreenLang.CREATOR_SUBMITTING);
        submitTicks = SUBMIT_REFRESH_TICKS;
    }

    /** Drop what the last Load said — it was about a build that is no longer the one on screen. */
    private void forgetLastLoad() {
        creatorNote = null;
        loadAsCopy = false;
        takenNames = List.of();
    }

    /**
     * Walk to the plot the selected build was loaded onto.
     *
     * <p>The header's answer to "it is here now": a category switch when it is not the one being
     * stood in, then the enter command for the template — the same walk My Builds makes after a
     * download, through the same helper.</p>
     */
    private void goToLoadedBuild() {
        BuilderProfilePacket.Entry entry = selectedCreatorBuild();
        EditorCreatorBuilds.Landed landed = EditorCreatorBuilds.here(EditorRosterClient.index(), entry);
        if (landed == null) return;
        if (!EditorTemplateJumpBridge.go(landed.kind(), landed.id(), landed.subKind())) return;
        goingTo = landed;
        goingTicks = GOING_TIMEOUT_TICKS;
        afterCommand();
    }

    /** A download finished: say what happened, and pick up what landed. */
    private void onDownloadResult(BuilderProfileDownloadResultPacket packet) {
        creatorNote = EditorScreenLang.text(BuilderProfileScreen.noteKeyFor(packet.outcome()));
        boolean nameInUse = packet.outcome() == BuilderRelayDownload.Outcome.ALREADY_HERE
            || packet.outcome() == BuilderRelayDownload.Outcome.NAME_TAKEN;
        takenNames = nameInUse ? List.copyOf(packet.takenNames()) : List.of();
        loadAsCopy = nameInUse;
        // The build's Stages collide with ones already here: ask per Stage, then press again with
        // the answer. Nothing was written, so the selection this screen would replay from is intact.
        if (packet.outcome() == BuilderRelayDownload.Outcome.STAGE_CONFLICT && this.minecraft != null) {
            this.minecraft.setScreen(new BuilderProfileStageConflictScreen(this, packet.id(),
                packet.stageConflicts(), this::loadSelectedCreatorBuild));
            return;
        }
        if (packet.outcome() == BuilderRelayDownload.Outcome.INSTALLED) {
            // Remembered against the build that was asked for: the pane stops offering to load it
            // and offers the walk to it instead.
            BuilderProfilePacket.Entry entry = selectedCreatorBuild();
            if (entry != null) {
                EditorCreatorBuilds.landed(entry.relayId(), packet.kindId(), packet.id(), packet.subKind());
            }
            // It is a template now, so the roster has to be asked again before it will show one.
            afterCommand();
        }
    }

    /**
     * Keep the previewer's paging pointed at the selected build, and ask the relay what versions it
     * has of it.
     *
     * <p>A change of selection resets to the build as it is now, because a seq belongs to one relay
     * row and means nothing in another's history. The index ask goes out once per build — the cache
     * remembers the answer, including "no versions", so this costs a map lookup a frame after
     * that.</p>
     */
    private void trackVersionsOf(int relayId, String ownerUuid) {
        if (relayId != previewRelayId) {
            previewRelayId = relayId;
            previewSeq = 0;
        }
        if (relayId > 0 && RelayBuildPreviews.versions(relayId) == null) {
            // -1: the newest recorded frame, and the index of every seq with it.
            RelayBuildPreviews.request(relayId, ownerUuid, BuilderProfileState.live(), -1);
        }
    }

    /** Step the preview one version older or newer, and fetch it if it is not here yet. */
    private void pageVersion(boolean older) {
        int[] seqs = RelayBuildPreviews.versions(previewRelayId);
        if (seqs == null || seqs.length == 0) return;
        int next = older ? VersionStrip.older(seqs, previewSeq) : VersionStrip.newer(seqs, previewSeq);
        if (next == previewSeq) return;
        previewSeq = next;
        if (next != 0) {
            RelayBuildPreviews.request(previewRelayId,
                EditorCreatorBuilds.active() ? EditorCreatorBuilds.ownerOf(selectedCreatorBuild()) : "",
                BuilderProfileState.live(), next);
        }
    }

    /**
     * Load all: every template of the chosen category into the world, the plots cleared first.
     *
     * <p>The category switch is exactly that — it tears down every plot of every category and
     * stamps this one's models — so this is the button for it rather than a second way to do the
     * same writes. Only under a category cell: All is four categories at once, which the editor
     * stamps one of, and a builder's uploads are not this world's to stamp.</p>
     */
    private void loadAllInCategory() {
        PlotCategory category = EditorScreenState.category().category();
        if (category == null) return;
        CommandRunner.run("dungeontrain editor " + category.owner().id());
        afterCommand();
    }

    /** Choose the variant parent the selected build will be filed under when it is loaded. */
    private void pickLoadParent() {
        BuilderProfilePacket.Entry entry = selectedCreatorBuild();
        if (entry == null || !CreatorLoadParent.supports(entry.kind())) return;
        PlotCategory category = EditorCreatorBuilds.categoryOf(entry.kind());
        if (category == null) return;
        modal.open(new CreatorParentPickerScreen(category, modal::pop));
    }

    /** The builder's upload the browser has selected, or null. */
    private static BuilderProfilePacket.Entry selectedCreatorBuild() {
        return EditorCreatorBuilds.byId(EditorCreatorBuilds.selectedId());
    }

    private void drawTooltips(GuiGraphics g, int mouseX, int mouseY) {
        if (search.isOpen()) {
            // The one hover text the panel carries: which relay its light is showing.
            String lightTip = search.tooltipAt(mouseX, mouseY);
            if (lightTip != null) g.renderTooltip(this.font, Component.literal(lightTip), mouseX, mouseY);
            return;
        }
        if (modal.isOpen() || inlineEdit.active()) return;
        String tip = null;
        if (hoveredTab != null && hoveredTab.kind() == EditorTabBar.Kind.EXIT) {
            tip = EditorScreenLang.text(EditorScreenLang.TAB_EXIT);
        } else if (hasFilterBar() && filterBar.hovered().kind() != EditorFilterBar.HitKind.NONE) {
            tip = filterBar.tooltipAt(filterBar.hovered());
        } else if (EditorScreenState.page().isBrowser()) {
            tip = browser.tooltipAt(browser.hovered());
        } else if (EditorScreenState.page() == EditorScreenPage.LAYOUT) {
            tip = layoutPane.tooltipAt(layout, EditorRosterClient.index(), mouseX, mouseY);
        }
        if (tip != null) {
            g.renderTooltip(this.font, Component.literal(tip), mouseX, mouseY);
            return;
        }
        List<String> lines = detail.tooltipAt(detail.hovered());
        if (!lines.isEmpty()) {
            g.renderComponentTooltip(this.font,
                lines.stream().map(l -> (net.minecraft.network.chat.Component) Component.literal(l)).toList(),
                mouseX, mouseY);
        }
    }

    private EditorScreenActions.Ctx context(EditorRosterIndex index) {
        VariantKey standing = EditorScreenState.standingIn();
        VariantKey selection = EditorScreenState.selection();
        EditorRosterIndex.Tile tile = selection == null ? null : index.find(selection);
        boolean dirty = false;
        if (tile != null) {
            PlotCategory cat = tile.key().category();
            dirty = EditorSaveStatus.isDirty(EditorStatusHudOverlay.unsavedList(), cat.id(),
                EditorSaveStatus.dirtyKey(cat, tile.key().modelId(), tile.key().modelName()));
        }
        return new EditorScreenActions.Ctx(
            tile == null ? null : tile.key(),
            tile == null ? null : tile.variant(),
            tile == null ? -1 : tile.selfWeight(),
            standing, index.stampedCategory(), dirty);
    }

    private float frameSeconds() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return 0.0F;
        return Math.min((now - previous) / 1.0E9F, MAX_FRAME_SECONDS);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (modal.isOpen()) {
            return modal.mouseClicked(mouseX, mouseY);
        }
        if (search.isOpen()) {
            onSearchClick(search.mouseClicked(mouseX, mouseY));
            return true;
        }
        // A click anywhere else abandons a half-typed value rather than leaving it hanging over
        // a cell whose row may be about to change underneath it.
        if (inlineEdit.active()) inlineEdit.cancel();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (layout == null) return super.mouseClicked(mouseX, mouseY, button);

        EditorTabBar.Tab tab = EditorTabBar.hit(tabs, layout.tabs(), mouseX, mouseY);
        if (tab != null) {
            click();
            onTab(tab);
            return true;
        }
        if (hasFilterBar()) {
            if (filterBox.mouseClicked(mouseX, mouseY, button)) {
                setFocused(filterBox);
                return true;
            }
            EditorFilterBar.Hit filterHit = filterBar.hitTest(mouseX, mouseY);
            if (filterHit.kind() != EditorFilterBar.HitKind.NONE) {
                click();
                onFilterHit(filterHit);
                return true;
            }
        }
        if (EditorScreenState.page().isBrowser()) {
            EditorBrowserPane.Hit hit = browser.hitTest(mouseX, mouseY);
            if (hit.kind() != EditorBrowserPane.HitKind.NONE) {
                click();
                onBrowserHit(hit);
                return true;
            }
        } else if (EditorScreenState.page() == EditorScreenPage.SETTINGS) {
            EditorSettingsPane.Hit hit = settingsPane.hitTest(layout, mouseX, mouseY);
            if (hit != null) {
                click();
                dispatchAt(hit.entry(), hit.sub());
                return true;
            }
        } else if (EditorScreenState.page() == EditorScreenPage.LAYOUT) {
            if (layoutPane.mouseClicked(layout, EditorRosterClient.index(), mouseX, mouseY, modal, inlineEdit)) {
                click();
                setFocused(null);
                return true;
            }
        }
        if (EditorCreatorBuilds.active()) {
            switch (creatorPane.hitTest(mouseX, mouseY)) {
                case OLDER -> {
                    click();
                    pageVersion(true);
                    return true;
                }
                case NEWER -> {
                    click();
                    pageVersion(false);
                    return true;
                }
                case LOAD -> {
                    click();
                    loadSelectedCreatorBuild();
                    return true;
                }
                case PARENT -> {
                    click();
                    pickLoadParent();
                    return true;
                }
                case SUBMIT -> {
                    click();
                    submitSelectedCreatorBuild();
                    return true;
                }
                case GO_HERE -> {
                    click();
                    goToLoadedBuild();
                    return true;
                }
                case PREVIEW -> {
                    orbit.beginDrag();
                    return true;
                }
                case NONE -> { }
            }
            setFocused(null);
            return super.mouseClicked(mouseX, mouseY, button);
        }
        EditorDetailPane.Hit hit = detail.hitTest(mouseX, mouseY);
        if (hit.kind() == EditorDetailPane.HitKind.PREVIEW) {
            orbit.beginDrag();
            return true;
        }
        if (hit.kind() != EditorDetailPane.HitKind.NONE) {
            if (onDetailHit(hit)) click();
            return true;
        }
        setFocused(null);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onTab(EditorTabBar.Tab tab) {
        EditorRosterIndex index = EditorRosterClient.index();
        switch (tab.kind()) {
            case EXIT -> modal.runAndClose("dungeontrain editor exit");
            case PAGE -> {
                EditorScreenState.setPage(tab.page());
                browser.resetScroll();
            }
        }
    }

    /** A click on the filter bar, which both roster tabs share — so both lists start over. */
    private void onFilterHit(EditorFilterBar.Hit hit) {
        switch (hit.kind()) {
            case TOGGLE -> EditorScreenState.toggleFilters();
            case CHIP -> {
                if (filterBar.isPlayerChip(hit.index())) {
                    openCreatorSearch();
                    return;
                }
                // Creator mode's own two chips narrow relay builds, which the roster's filter record
                // knows nothing about — so they are applied first, and on their own terms.
                if (!filterBar.applyCreatorChip(hit.index())) {
                    EditorScreenState.setFilters(filterBar.applyChip(hit.index(), EditorScreenState.filters()));
                }
            }
            case ACTIVE -> {
                if (filterBar.isPlayerActive(hit.index())) {
                    openCreatorSearch();
                    return;
                }
                filterBar.applyActive(hit.index());
            }
            case CATEGORY -> {
                EditorCategoryFilter cell = filterBar.categoryAt(hit.index());
                if (cell != null) EditorScreenState.setCategory(cell);
            }
            case STRIP -> {
                EditorRosterIndex.TypeStrip strip = filterBar.stripAt(hit.index());
                if (strip != null) EditorScreenState.setTypeName(strip.typeName());
            }
            case LOAD_ALL -> loadAllInCategory();
            case NONE -> { }
        }
        browser.resetScroll();
        layoutPane.resetScroll();
    }

    private void onBrowserHit(EditorBrowserPane.Hit hit) {
        VariantKey standing = EditorScreenState.standingIn();
        EditorRosterIndex index = EditorRosterClient.index();
        switch (hit.kind()) {
            case TILE -> selectOrEnter(browser.tiles().get(hit.index()).key());
            case CREATOR_TILE -> {
                EditorCreatorBuilds.select(browser.creatorTiles().get(hit.index()).relayId());
                goingTo = null;
                // The note and the copy offer belonged to the build that was selected before.
                forgetLastLoad();
            }
            // Starring is not selecting: pressing the corner of a tile you are not looking at should
            // keep it for later without moving the previewer off whatever you were reading.
            case CREATOR_STAR -> EditorCreatorBuilds.toggleStar(browser.creatorTiles().get(hit.index()));
            case SUB_TILE -> {
                if (hit.index() == -1) selectOrEnter(browser.subParent().key());
                else selectOrEnter(browser.subTiles().get(hit.index()).key());
            }
            case NEW -> {
                PlotCategory page = EditorScreenState.category().category();
                EditorRosterIndex.TypeStrip strip = stripByName(index, page, EditorScreenState.effectiveTypeName(index));
                if (strip == null) return;
                List<EditorRosterIndex.Tile> all = index.tiles(page, strip.typeName());
                String first = all.isEmpty() ? "" : all.get(0).key().displayName();
                dispatch(EditorScreenActions.newEntry(strip.category(), strip.modelId(), first, standing));
            }
            case NEW_SUB -> dispatch(EditorScreenActions.newSubVariantEntry(
                browser.subParent() == null ? null : browser.subParent().key(), standing));
            default -> { }
        }
    }

    /**
     * Find a player and open their uploaded builds — the same two screens the pause menu's My
     * Builds uses, so a reviewer follows one flow rather than two that look alike.
     *
     * <p>Their profile replaces this screen and comes back to it on Back. Nothing about the editor
     * roster changes: these are builds on the relay, not templates on this machine.</p>
     */
    private void openCreatorSearch() {
        setFocused(null);   // the filter box must not eat what is typed into the panel
        search.open();
    }

    /** What the search panel's click meant: a builder to load, the way back, or nothing. */
    private void onSearchClick(EditorCreatorSearch.Result result) {
        switch (result.outcome()) {
            case PICKED -> {
                click();
                EditorCreatorBuilds.show(result.creator().uuid(), result.creator().name());
                forgetLastLoad();
                browser.resetScroll();
                search.close();
            }
            case ALL -> {
                click();
                EditorCreatorBuilds.showAll();
                forgetLastLoad();
                browser.resetScroll();
                search.close();
            }
            case CLEARED -> {
                click();
                EditorCreatorBuilds.clear();
                forgetLastLoad();
                browser.resetScroll();
                search.close();
            }
            case RELAY_TOGGLED -> {
                click();
                // The same switch the Settings tab carries, minus the trip there and back. The
                // panel stays open: the point of flipping it here is to re-ask the name typed.
                EditorSettingsPage.setRelay(!BuilderProfileState.live());
                EditorCreatorBuilds.requestFavourites();
                EditorCreatorBuilds.refresh();
                forgetLastLoad();
                search.rearm();
            }
            // A click outside the panel closes it, the way clicking off any picker does.
            case NONE -> search.close();
            case CONSUMED -> { }
        }
    }

    private static EditorRosterIndex.TypeStrip stripByName(EditorRosterIndex index, PlotCategory page, String name) {
        if (page == null) return null;
        for (EditorRosterIndex.TypeStrip s : index.typeStrips(page)) {
            if (s.typeName().equals(name)) return s;
        }
        return null;
    }

    /** Single click selects; a second click on the same tile within the window enters it. */
    private void selectOrEnter(VariantKey key) {
        long now = System.currentTimeMillis();
        boolean doubleClick = key.equals(lastClickKey) && now - lastClickMillis < DOUBLE_CLICK_MS;
        lastClickKey = key;
        lastClickMillis = now;
        EditorScreenState.select(key);
        if (doubleClick) {
            dispatch(EditorScreenActions.enterEntry(context(EditorRosterClient.index())));
        }
    }

    private boolean onDetailHit(EditorDetailPane.Hit hit) {
        switch (hit.kind()) {
            case ICON -> {
                EditorScreenActions.Icon icon = detail.icons().get(hit.index());
                if (!icon.enabled()) return false;
                dispatch(icon.entry());
                return true;
            }
            case ROW -> {
                dispatchAt(detail.rows().get(hit.index()), hit.sub());
                return true;
            }
            case SHEET -> {
                TemplateDataSheet.Placed placed = detail.sheetCell(hit.index());
                if (placed == null) return false;
                return onSheetCell(placed);
            }
            case TEST -> {
                if (detail.testEntry() == null) return false;
                dispatch(detail.testEntry());
                return true;
            }
            case GO_HERE -> {
                if (detail.goHereEntry() == null) return false;
                dispatch(detail.goHereEntry());
                return true;
            }
            case OLDER -> {
                pageVersion(true);
                return true;
            }
            case NEWER -> {
                pageVersion(false);
                return true;
            }
            default -> { return false; }
        }
    }

    /** A click on a data-sheet cell: type over it, run its command, or open its picker. */
    private boolean onSheetCell(TemplateDataSheet.Placed placed) {
        TemplateDataSheet.Action action = placed.cell().action();
        if (action instanceof TemplateDataSheet.Action.Type type) {
            inlineEdit.begin(type.prefix(), placed.cell().text(), placed.rect());
            setFocused(null);
            return true;
        }
        if (action instanceof TemplateDataSheet.Action.Run run) {
            CommandRunner.run(run.command());
            afterCommand();
            return true;
        }
        // Same gestures as the Layout tab's weight cell: click +1, shift-click −1, cmd-click
        // types. The typed value goes through the inline field over the cell, as Type does.
        if (action instanceof TemplateDataSheet.Action.Step step) {
            if (MenuClickModifiers.cmdDown()) {
                inlineEdit.begin(step.prefix(), placed.cell().text(), placed.rect());
                setFocused(null);
            } else {
                CommandRunner.run(Screen.hasShiftDown() ? step.dec() : step.inc());
                afterCommand();
            }
            return true;
        }
        if (action instanceof TemplateDataSheet.Action.Open open) {
            modal.open(open.screen());
            return true;
        }
        return false;
    }

    private void dispatch(CommandMenuEntry entry) {
        dispatchAt(entry, 0);
    }

    private void dispatchAt(CommandMenuEntry entry, int sub) {
        if (entry == null) return;
        modal.dispatch(entry, sub);
    }

    private void click() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (orbit.isDragging()) {
            orbit.drag((float) dragX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (orbit.isDragging()) {
            orbit.endDrag();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** The wheel scrolls whichever list is under it; anywhere else it reaches the hotbar. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        if (modal.isOpen()) return modal.mouseScrolled(mouseX, mouseY, scrollY);
        int dir = scrollY > 0 ? -1 : 1;
        if (search.isOpen()) return search.scrollBy(dir);
        if (EditorScreenState.page().isBrowser() && browser.overGrid(mouseX, mouseY)) {
            if (browser.scrollBy(dir)) return true;
        }
        if (EditorScreenState.page() == EditorScreenPage.SETTINGS && layout != null
                && EditorSettingsPane.rect(layout).contains(mouseX, mouseY)) {
            return settingsPane.scrollBy(dir);
        }
        if (EditorScreenState.page() == EditorScreenPage.LAYOUT && layout != null
                && layoutPane.over(layout, mouseX, mouseY)) {
            // A half-typed value must not float over a list that just moved under it.
            inlineEdit.cancel();
            return layoutPane.scrollBy(dir);
        }
        if (detail.overSettings(mouseX, mouseY) && detail.scrollBy(dir)) return true;
        if (HotbarPassthrough.scroll(this.minecraft, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inlineEdit.active()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    String command = inlineEdit.submit();
                    if (command != null) {
                        CommandRunner.run(command);
                        afterCommand();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> { inlineEdit.cancel(); return true; }
                case GLFW.GLFW_KEY_BACKSPACE -> { inlineEdit.backspace(); return true; }
                default -> { return true; }
            }
        }
        if (search.isOpen()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE -> { search.close(); return true; }
                case GLFW.GLFW_KEY_BACKSPACE -> { search.backspace(); return true; }
                // Everything else is swallowed: the panel is a text field, and X would close the
                // screen out from under a half-typed name.
                default -> { return true; }
            }
        }
        if (modal.isTyping()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { modal.submitTyped(); return true; }
                case GLFW.GLFW_KEY_ESCAPE -> { modal.cancelTyping(); return true; }
                case GLFW.GLFW_KEY_BACKSPACE -> { modal.backspace(); return true; }
                default -> { return true; }
            }
        }
        if (filterBox != null && filterBox.isFocused() && filterBox.visible) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                setFocused(null);
                return true;
            }
            return filterBox.keyPressed(keyCode, scanCode, modifiers) || true;
        }
        if (CommandMenuKeyBindings.TOGGLE.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (!modal.pop()) this.onClose();
            return true;
        }
        if (HotbarPassthrough.key(this.minecraft, keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (search.isOpen()) return search.charTyped(codePoint);
        if (inlineEdit.active()) return inlineEdit.charTyped(codePoint);
        if (modal.isTyping()) return modal.charTyped(codePoint);
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        search.close();
        modal.closeAll();
        super.onClose();
    }

    /** Whether any plot anywhere has unsaved edits — the header icon's colour. */
    static boolean anyDirty(List<EditorDirtyCheck.DirtyEntry> rows) {
        if (rows == null) return false;
        for (EditorDirtyCheck.DirtyEntry row : rows) {
            if (row.isUnsaved()) return true;
        }
        return false;
    }
}
