package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuEntryDispatcher;

import games.brennan.dungeontrain.client.menu.number.NumberStepper;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.builder.BuilderTilePreviews;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.CommandMenuKeyBindings;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.EditorSaveStatus;
import games.brennan.dungeontrain.client.menu.HotbarPassthrough;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorScreenTheme;
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
    static final int GOING_TIMEOUT_TICKS = 200;
    /**
     * How long a Load is given before the button comes back and the note says it did not land.
     *
     * <p>A minute: the server's fetch of a big build alone can run to the relay client's own
     * ten-second limit, and a 40k-block portal room then takes seconds more to install and stamp.
     * The button is greyed for all of it, because a second press would fetch it all again.</p>
     */
    static final int LOAD_TIMEOUT_TICKS = 1200;

    private final EditorFilterBar filterBar = new EditorFilterBar();
    private final EditorBrowserPane browser = new EditorBrowserPane();
    private final EditorDetailPane detail = new EditorDetailPane();
    private final EditorCreatorPane creatorPane = new EditorCreatorPane();
    private final EditorSettingsPane settingsPane = new EditorSettingsPane();
    private final EditorLayoutPane layoutPane = new EditorLayoutPane(this::selectOrEnter);
    private final EditorStagesPane stagesPane = new EditorStagesPane();
    private final EditorNavPane navPane = new EditorNavPane();
    private final EditorStageDetailPane stageDetail = new EditorStageDetailPane();
    private final EditorGroupsTab groupsTab = new EditorGroupsTab();
    private final OrbitState orbit = new OrbitState();
    private final InlineEdit inlineEdit = new InlineEdit();
    private final EditorModalHost modal = new EditorModalHost(this::onClose, this::afterCommand);
    private final EditorCreatorSearch search = new EditorCreatorSearch();
    private final EditorCreatorLoadController creator = new EditorCreatorLoadController(new CreatorHost());

    private EditBox filterBox;
    private InventoryEditorLayout layout;
    private List<EditorTabBar.Tab> tabs = List.of();
    private EditorTabBar.Tab hoveredTab;
    private long lastFrameNanos;
    private VariantKey lastClickKey;
    private long lastClickMillis;
    private VariantKey previewKey;
    private int refreshTicks;
    /**
     * The selection as of the last frame, and whether it had unsaved work then — so a save of it
     * (dirty turning clean) can drop its baked model. The model is baked from the file once per
     * screen, so without this the preview kept showing the build from before the save until X was
     * reopened. Relay uploads refresh it too (EditorUploadStatus); this covers saves that stay local.
     */
    private VariantKey dirtyFor;
    private boolean wasDirty;

    public EditorGuiScreen() {
        super(Component.translatable("gui.dungeontrain.editor_screen.title"));
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
            Component.translatable("gui.dungeontrain.editor_screen.filters"));
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
        BuilderProfileState.listenForDownloads(creator::onDownloadResult);
    }

    @Override
    public void removed() {
        super.removed();
        BuilderTilePreviews.clear();
        RelayBuildPreviews.clear();
        games.brennan.dungeontrain.client.builder.WorkbenchPreviews.clear();
        games.brennan.dungeontrain.client.builder.StagePreviews.clear();
        search.close();
        EditorCreatorBuilds.detach();
        BuilderProfileState.listenForDownloads(null);
    }

    /** The two tabs that browse the roster and so carry the filter bar; Stages, Nav and Settings do not. */
    private static boolean hasFilterBar() {
        EditorScreenPage page = EditorScreenState.page();
        return page != EditorScreenPage.SETTINGS && page != EditorScreenPage.STAGES && page != EditorScreenPage.NAV
            && page != EditorScreenPage.GROUPS;
    }

    /** Whether the Groups tab owns the screen — both columns. */
    private static boolean onGroups() {
        return EditorScreenState.page() == EditorScreenPage.GROUPS;
    }

    /** The Groups tab is a Tracks tab: tunnel template groups are all it shows. */
    private static boolean groupsShown() {
        return EditorScreenState.category() == EditorCategoryFilter.TRACKS;
    }

    /** Whether the Nav tab owns the screen — both columns, no template or stage detail. */
    private static boolean onNav() {
        return EditorScreenState.page() == EditorScreenPage.NAV;
    }

    /** Whether the right pane is the stage detail rather than the template detail. */
    private static boolean onStages() {
        return EditorScreenState.page() == EditorScreenPage.STAGES;
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
        creator.tick();
        if (refreshTicks > 0 && --refreshTicks == 0) {
            EditorSaveStatus.request();
        }
    }

    /** The selection just went from unsaved to saved: re-bake its model from the file the save wrote. */
    private void refreshModelAfterSave(EditorScreenActions.Ctx ctx) {
        VariantKey selection = ctx.selection();
        if (selection != null && selection.equals(dirtyFor) && wasDirty && !ctx.dirty()) {
            EditorUploadStatus.evictModel(TemplateArt.of(selection));
        }
        dirtyFor = selection;
        wasDirty = ctx.dirty();
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
        games.brennan.dungeontrain.client.builder.StagePreviews.beginFrame();

        EditorScreenActions.Ctx ctx = context(index);
        refreshModelAfterSave(ctx);
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
        // Leaving Tracks takes the Groups tab away; the screen goes back to Templates with it.
        if (onGroups() && !groupsShown()) EditorScreenState.setPage(EditorScreenPage.TEMPLATES);
        tabs = EditorTabBar.layout(layout.tabs(), this.font::width, p -> EditorScreenLang.text(p.langKey()),
            groupsShown());
        hoveredTab = modal.isOpen() || search.isOpen() ? null
            : EditorTabBar.hit(tabs, layout.tabs(), mouseX, mouseY);
        // The standing plot's category is a cell of the Templates tab now, so that is the tab that
        // wears the dot; the browser pane marks the cell itself.
        EditorTabBar.draw(g, this.font, layout.tabs(), tabs, EditorScreenState.page(),
            ctx.standing() == null ? null : EditorScreenPage.TEMPLATES,
            ctx.dirty(), hoveredTab, theme);

        boolean overlaid = modal.isOpen() || search.isOpen();
        // Rows under an open stage dropdown don't light up through it.
        boolean covered = overlaid || (onStages() && stagesPane.overDropdown(mouseX, mouseY));
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
        } else if (onStages()) {
            // The tiles follow the overview's carriage and roll, laid out a frame ago — close enough.
            stagesPane.followModel(stageDetail.carriageShown(), stageDetail.seedShown());
            stagesPane.render(g, this.font, theme, layout, index, mx, my);
        }

        if (onGroups()) {
            groupsTab.render(g, this.font, theme, layout, orbit.yaw(), mx, my);
        } else if (onNav()) {
            // Nav owns the right column too: the picked area's picture and words stand where a
            // template's model and sheet would, and none of the template controls apply to an area.
            navPane.render(g, this.font, theme, layout, index, mx, my);
        } else if (EditorCreatorBuilds.active()) {
            // A relay row is not a template: none of the detail pane's controls apply to one, so
            // the pane that has no controls stands in for it rather than eight disabled buttons.
            BuilderProfilePacket.Entry picked = EditorCreatorLoadController.selected();
            creator.trackVersionsOf(picked == null ? 0 : picked.relayId(),
                EditorCreatorBuilds.ownerOf(picked));
            creatorPane.workbenchTab(EditorScreenState.onWorkbenchTab(), browser.creatorTiles().size());
            creatorPane.render(g, this.font, layout, theme, picked, orbit.yaw(),
                creator.note(), creator.loadAsCopy(), EditorCreatorBuilds.here(index, picked), creator.going(),
                creator.loading(), creator.previewSeq(), mx, my);
        } else if (onStages()) {
            stageDetail.layout(layout, EditorScreenState.effectiveStage(index), index);
            stageDetail.render(g, this.font, theme, orbit.yaw(), mx, my);
        } else {
            EditorRosterIndex.Tile tile = ctx.hasSelection() ? index.find(ctx.selection()) : null;
            TemplateArt art = TemplateArt.of(ctx.selection());
            boolean staged = ctx.hasSelection() && ctx.selection().category() == PlotCategory.WORKBENCH;
            TemplateSummary summary = staged
                ? games.brennan.dungeontrain.client.builder.WorkbenchPreviews.summary(ctx.selection().modelName())
                : art == null ? null : art.summary();
            creator.trackVersionsOf(tile == null ? 0 : tile.relayId(), "");
            detail.showVersion(tile == null ? 0 : tile.relayId(), creator.previewSeq());
            detail.showSummary(summary);
            detail.layout(layout, ctx, System.currentTimeMillis());
            detail.render(g, this.font, theme, art, summary, tile,
                EditorDetailPane.pathLabel(index, ctx.selection()), orbit.yaw(), mx, my);
        }

        String empty = browsing ? emptyGridNote(index) : null;
        if (empty != null) {
            g.drawString(this.font, empty, layout.grid().x() + 4, layout.grid().y() + 4, 0xFFFFFFFF, true);
        }

        // A stage family's dropdown spills over the detail pane, so it goes on after every pane.
        if (onStages()) stagesPane.renderDropdown(g, this.font, index, this.width, this.height,
            overlaid ? -1 : mouseX, overlaid ? -1 : mouseY);
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
        if (onNav()) {
            // Nav splits the pane its own way (a narrow tile list, a wide preview), so the two
            // sub-panels follow its columns — the browser's split would leave a seam down the preview.
            InventoryEditorLayout.Rect tiles = EditorNavPane.rect(layout);
            InventoryEditorLayout.Rect detail = EditorNavPane.detail(layout);
            g.fill(tiles.x() - 1, tiles.y() - 1, tiles.right() + 1, tiles.bottom() + 1, theme.subPanel());
            g.fill(detail.x() - 1, detail.y() - 1, detail.right() + 1, detail.bottom() + 1, theme.subPanel());
            return;
        }
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
            standing, index.stampedCategory(), dirty,
            tile == null ? null : tile.extras());
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

    /**
     * The button of the click being handled — read by the number cells, which step down on a
     * right-click. Everything else on this screen only ever sees a left click.
     */
    private int clickButton;

    private ScreenInputGate.State inputState() {
        return new ScreenInputGate.State(modal.isOpen(), modal.isTyping(), search.isOpen(), inlineEdit.active(),
            filterBox != null && filterBox.isFocused() && filterBox.visible,
            onStages() && stagesPane.dropdownOpen());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        clickButton = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? 1 : 0;
        ScreenInputGate.State state = inputState();
        switch (ScreenInputGate.click(state)) {
            case MODAL -> { return modal.mouseClicked(mouseX, mouseY, clickButton); }
            case SEARCH -> {
                onSearchClick(search.mouseClicked(mouseX, mouseY));
                return true;
            }
            default -> { }
        }
        if (ScreenInputGate.clickCancelsInlineEdit(state)) inlineEdit.cancel();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && rightClickNumber(mouseX, mouseY)) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (layout == null) return super.mouseClicked(mouseX, mouseY, button);
        // An open stage dropdown takes the next click, wherever it lands: a member picks, the rest shuts it.
        if (ScreenInputGate.click(state) == ScreenInputGate.Layer.STAGE_DROPDOWN) {
            click();
            stagesPane.dropdownClicked(mouseX, mouseY);
            return true;
        }
        for (EditorPane pane : panes) {
            if (pane.active() && pane.mouseClicked(mouseX, mouseY, button)) return true;
        }
        setFocused(null);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * A right-click: the step-down of whichever number cell is under the cursor — a sheet number, a
     * Layout weight, a number row in a pane — and nothing anywhere else. Probed without acting on
     * what is not a number, so a right-click never selects, opens or toggles.
     */
    private boolean rightClickNumber(double mouseX, double mouseY) {
        if (layout == null) return false;
        for (EditorPane pane : panes) {
            if (pane.active() && pane.rightClickNumber(mouseX, mouseY)) return true;
        }
        return false;
    }

    /** The wheel scrolls whichever list is under it; anywhere else it reaches the hotbar. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int dir = scrollY > 0 ? -1 : 1;
        switch (ScreenInputGate.scroll(inputState())) {
            case MODAL -> { return modal.mouseScrolled(mouseX, mouseY, scrollY); }
            case SEARCH -> { return search.scrollBy(dir); }
            default -> { }
        }
        if (layout != null) {
            for (EditorPane pane : panes) {
                if (pane.active() && pane.scrollBy(mouseX, mouseY, dir)) return true;
            }
        }
        if (HotbarPassthrough.scroll(this.minecraft, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void drawTooltips(GuiGraphics g, int mouseX, int mouseY) {
        // Nothing under an open stage dropdown speaks through it.
        if (onStages() && stagesPane.overDropdown(mouseX, mouseY)) return;
        if (search.isOpen()) {
            // The one hover text the panel carries: which relay its light is showing.
            String lightTip = search.tooltipAt(mouseX, mouseY);
            if (lightTip != null) g.renderTooltip(this.font, Component.literal(lightTip), mouseX, mouseY);
            return;
        }
        if (modal.isOpen() || inlineEdit.active()) return;
        for (EditorPane pane : panes) {
            if (!pane.active()) continue;
            EditorPane.Tooltip tip = pane.tooltipAt(mouseX, mouseY);
            if (tip.isEmpty()) continue;
            List<Component> text = tip.lines().stream().map(l -> (Component) Component.literal(l)).toList();
            if (tip.icons().isEmpty()) {
                g.renderComponentTooltip(this.font, text, mouseX, mouseY);
            } else {
                // The same icon grid a prefab's tooltip uses, under the text.
                g.renderTooltip(this.font, text, java.util.Optional.of(
                    new games.brennan.dungeontrain.client.tooltip.PrefabIconsTooltipData(tip.icons(), tip.icons().size())),
                    mouseX, mouseY);
            }
            return;
        }
    }

    // ------------------------------------------------------------------
    // Panes, in router order
    // ------------------------------------------------------------------

    /**
     * Every region of the screen in the order the routers try them: the tab bar and the filter bar,
     * the left column's page pane (browser / settings / layout / stages list / groups / nav), then
     * the right column's (creator / stage detail / template detail). Groups and Nav own both
     * columns, so the right-column panes stand down while either is up.
     */
    private final List<EditorPane> panes = List.of(
        new TabsPane(), new FilterPane(),
        new BrowserPane(), new SettingsPanePane(), new LayoutPanePane(), new StagesListPane(),
        new GroupsPane(), new NavPane(),
        new CreatorPanePane(), new StageDetailPane(), new DetailPanePane());

    /** Whether the right column shows a pane of its own — not under Groups or Nav. */
    private static boolean rightColumnShown() {
        return !onGroups() && !onNav();
    }

    private final class TabsPane implements EditorPane {
        @Override public boolean active() { return true; }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorTabBar.Tab tab = EditorTabBar.hit(tabs, layout.tabs(), mx, my);
            if (tab == null) return false;
            click();
            onTab(tab);
            return true;
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return hoveredTab != null && hoveredTab.kind() == EditorTabBar.Kind.EXIT
                ? Tooltip.of(EditorScreenLang.text(EditorScreenLang.TAB_EXIT)) : Tooltip.NONE;
        }
    }

    private final class FilterPane implements EditorPane {
        @Override public boolean active() { return hasFilterBar(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (filterBox.mouseClicked(mx, my, button)) {
                setFocused(filterBox);
                return true;
            }
            EditorFilterBar.Hit filterHit = filterBar.hitTest(mx, my);
            if (filterHit.kind() == EditorFilterBar.HitKind.NONE) return false;
            click();
            onFilterHit(filterHit);
            return true;
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return filterBar.hovered().kind() != EditorFilterBar.HitKind.NONE
                ? Tooltip.of(filterBar.tooltipAt(filterBar.hovered())) : Tooltip.NONE;
        }
    }

    private final class BrowserPane implements EditorPane {
        @Override public boolean active() { return EditorScreenState.page().isBrowser(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorBrowserPane.Hit hit = browser.hitTest(mx, my);
            if (hit.kind() == EditorBrowserPane.HitKind.NONE) return false;
            click();
            onBrowserHit(hit);
            return true;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            return browser.overGrid(mx, my) && browser.scrollBy(dir);
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return Tooltip.of(browser.tooltipAt(browser.hovered()));
        }
    }

    private final class SettingsPanePane implements EditorPane {
        @Override public boolean active() { return EditorScreenState.page() == EditorScreenPage.SETTINGS; }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorSettingsPane.Hit hit = settingsPane.hitTest(layout, mx, my);
            if (hit == null) return false;
            click();
            dispatchAt(hit.entry(), hit.sub());
            return true;
        }

        @Override
        public boolean rightClickNumber(double mx, double my) {
            EditorSettingsPane.Hit hit = settingsPane.hitTest(layout, mx, my);
            if (hit == null || !MenuEntryDispatcher.isNumber(hit.entry(), hit.sub())) return false;
            click();
            modal.dispatch(hit.entry(), hit.sub(), 1);
            return true;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            if (!EditorSettingsPane.rect(layout).contains(mx, my)) return false;
            settingsPane.scrollBy(dir);
            return true;
        }
    }

    private final class LayoutPanePane implements EditorPane {
        @Override public boolean active() { return EditorScreenState.page() == EditorScreenPage.LAYOUT; }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!layoutPane.mouseClicked(layout, EditorRosterClient.index(), mx, my, modal, inlineEdit)) return false;
            click();
            setFocused(null);
            return true;
        }

        @Override
        public boolean rightClickNumber(double mx, double my) {
            if (!layoutPane.mouseClicked(layout, EditorRosterClient.index(), mx, my, modal, inlineEdit, 1)) return false;
            click();
            return true;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            if (!layoutPane.over(layout, mx, my)) return false;
            // A half-typed value must not float over a list that just moved under it.
            inlineEdit.cancel();
            layoutPane.scrollBy(dir);
            return true;
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return Tooltip.of(layoutPane.tooltipAt(layout, EditorRosterClient.index(), (int) mx, (int) my));
        }
    }

    /** The Stages tab's left column: the stage tiles and a family's dropdown over them. */
    private final class StagesListPane implements EditorPane {
        @Override public boolean active() { return onStages(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!stagesPane.mouseClicked(layout, EditorRosterClient.index(), mx, my)) return false;
            click();
            setFocused(null);
            return true;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            if (stagesPane.overDropdown(mx, my)) {
                stagesPane.scrollDropdown(dir);
                return true;
            }
            if (!stagesPane.over(layout, mx, my)) return false;
            stagesPane.scrollBy(dir);
            return true;
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return Tooltip.of(stagesPane.tooltipAt(layout, EditorRosterClient.index(), mx, my));
        }
    }

    /** The Groups tab owns both columns: whatever it does not take falls to the screen's children. */
    private final class GroupsPane implements EditorPane {
        @Override public boolean active() { return onGroups(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorGroupsTab.Click hit = groupsTab.click(mx, my);
            if (hit == null) return false;
            switch (hit) {
                case EditorGroupsTab.Click.Consumed c -> click();
                case EditorGroupsTab.Click.Entry e -> {
                    click();
                    dispatch(e.entry());
                }
                case EditorGroupsTab.Click.Open o -> {
                    click();
                    modal.open(o.screen());
                }
                case EditorGroupsTab.Click.Sheet s -> {
                    if (onSheetCell(s.placed())) click();
                }
                case EditorGroupsTab.Click.Preview p -> orbit.beginDrag();
            }
            setFocused(null);
            return true;
        }

        @Override
        public boolean rightClickNumber(double mx, double my) {
            EditorGroupsTab.Click hit = groupsTab.numberAt(mx, my);
            if (hit instanceof EditorGroupsTab.Click.Sheet s) return rightClickSheet(s.placed());
            if (hit instanceof EditorGroupsTab.Click.Entry e) {
                click();
                modal.dispatch(e.entry(), 0, 1);
                return true;
            }
            return false;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            if (!groupsTab.over(mx, my)) return false;
            groupsTab.scrollBy(mx, my, dir);
            return true;
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return Tooltip.of(groupsTab.tooltipAt(mx, my));
        }
    }

    /** The Nav tab owns both columns. */
    private final class NavPane implements EditorPane {
        @Override public boolean active() { return onNav(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorNavPane.Hit hit = navPane.hitTest(mx, my);
            if (hit.kind() == EditorNavPane.Kind.NONE) return false;
            click();
            onNavHit(hit);
            return true;
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            List<String> nav = navPane.tooltipAt(mx, my, EditorRosterClient.index());
            return nav.isEmpty() ? Tooltip.NONE : Tooltip.of(nav.get(0));
        }
    }

    /**
     * A relay row is not a template: none of the detail pane's controls apply to one, so the pane
     * that has no controls stands in for it rather than eight disabled buttons.
     */
    private final class CreatorPanePane implements EditorPane {
        @Override public boolean active() { return rightColumnShown() && EditorCreatorBuilds.active(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorCreatorPane.HitKind hit = creatorPane.hitTest(mx, my);
            if (hit == EditorCreatorPane.HitKind.NONE) return false;
            if (hit == EditorCreatorPane.HitKind.PREVIEW) {
                orbit.beginDrag();
                return true;
            }
            click();
            BuilderProfilePacket.Entry selected = EditorCreatorLoadController.selected();
            switch (hit) {
                case OLDER -> creator.pageVersion(true);
                case NEWER -> creator.pageVersion(false);
                case LOAD -> creator.loadSelected();
                case PARENT -> creator.pickLoadParent();
                case WORKBENCH -> creator.stageSelected();
                case AUTOLOAD -> creator.autoloadWorkbench(browser.creatorTiles());
                case SUBMIT -> creator.submitSelected();
                case GO_HERE -> creator.goToLoadedBuild();
                case PAGE_PREV -> creatorPane.turnSheet(-1);
                case PAGE_NEXT -> creatorPane.turnSheet(1);
                case EDIT_NOTE -> creatorPane.openNoteEditor(selected);
                case REVIEW_ACCEPT -> creatorPane.openReview(selected, BuilderReviewState.ACCEPTED);
                case REVIEW_FEEDBACK -> creatorPane.openReview(selected, BuilderReviewState.FEEDBACK);
                case REVIEW_DECLINE -> creatorPane.openReview(selected, BuilderReviewState.DECLINED);
                case REVIEW_RESUBMIT -> creatorPane.openReview(selected, BuilderReviewState.RESUBMIT);
                default -> { }
            }
            return true;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            if (!creatorPane.overSheet(mx, my)) return false;
            creatorPane.turnSheet(dir);
            return true;
        }
    }

    private final class StageDetailPane implements EditorPane {
        @Override public boolean active() { return rightColumnShown() && !EditorCreatorBuilds.active() && onStages(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorStageDetailPane.Hit stageHit = stageDetail.hitTest(mx, my);
            switch (stageHit.kind()) {
                case PAGE_PREV -> { if (stageDetail.scrollBy(-1)) click(); return true; }
                case PAGE_NEXT -> { if (stageDetail.scrollBy(+1)) click(); return true; }
                case ICON -> {
                    EditorScreenActions.Icon icon = stageDetail.icons().get(stageHit.index());
                    if (!icon.enabled()) return false;
                    click();
                    dispatch(icon.entry());
                    return true;
                }
                case PREVIEW -> {
                    orbit.beginDrag();
                    return true;
                }
                case SHEET -> {
                    TemplateDataSheet.Placed placed = stageDetail.sheetCell(stageHit.index());
                    if (placed == null) return false;
                    if (onSheetCell(placed)) click();
                    return true;
                }
                case CELL, FAMILY -> {
                    // The palette's gestures are the world-space panel's: the server reads the held
                    // block (or the empty hand) and answers on the action bar; the roster refresh
                    // that follows redraws the cell.
                    games.brennan.dungeontrain.net.StagePaletteEditPacket edit = stageDetail.editFor(stageHit);
                    if (edit == null) return false;
                    click();
                    DungeonTrainNet.sendToServer(edit);
                    afterCommand();
                    return true;
                }
                case ROW -> {
                    // A linked template's row is a shortcut to its tile: select it and let the
                    // browser open on it, as revealing any selection does.
                    VariantKey key = stageDetail.rowKey(stageHit);
                    if (key == null) return true;
                    click();
                    EditorScreenState.select(key);
                    EditorScreenState.revealSelection(EditorRosterClient.index());
                    browser.resetScroll();
                    return true;
                }
                default -> { return false; }
            }
        }

        @Override
        public boolean rightClickNumber(double mx, double my) {
            EditorStageDetailPane.Hit stageHit = stageDetail.hitTest(mx, my);
            if (stageHit.kind() != EditorStageDetailPane.HitKind.SHEET) return false;
            return rightClickSheet(stageDetail.sheetCell(stageHit.index()));
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            return stageDetail.over(mx, my) && stageDetail.scrollBy(dir);
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            return Tooltip.of(stageDetail.tooltipAt(stageDetail.hovered()));
        }
    }

    private final class DetailPanePane implements EditorPane {
        @Override public boolean active() { return rightColumnShown() && !EditorCreatorBuilds.active() && !onStages(); }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            EditorDetailPane.Hit hit = detail.hitTest(mx, my);
            if (hit.kind() == EditorDetailPane.HitKind.PREVIEW) {
                orbit.beginDrag();
                return true;
            }
            if (hit.kind() == EditorDetailPane.HitKind.NONE) return false;
            if (onDetailHit(hit)) click();
            return true;
        }

        @Override
        public boolean rightClickNumber(double mx, double my) {
            // As it always was: the browser tab's right-click reaches no number in the detail pane.
            if (EditorScreenState.page().isBrowser()) return false;
            EditorDetailPane.Hit hit = detail.hitTest(mx, my);
            if (hit.kind() == EditorDetailPane.HitKind.SHEET) return rightClickSheet(detail.sheetCell(hit.index()));
            if (hit.kind() == EditorDetailPane.HitKind.ROW) {
                CommandMenuEntry row = detail.rows().get(hit.index());
                if (!MenuEntryDispatcher.isNumber(row, hit.sub())) return false;
                click();
                modal.dispatch(row, hit.sub(), 1);
                return true;
            }
            return false;
        }

        @Override
        public boolean scrollBy(double mx, double my, int dir) {
            return detail.overSettings(mx, my) && detail.scrollBy(dir);
        }

        @Override
        public Tooltip tooltipAt(double mx, double my) {
            List<String> lines = detail.tooltipAt(detail.hovered());
            if (lines.isEmpty()) return Tooltip.NONE;
            return new Tooltip(lines, detail.tooltipIconsAt(detail.hovered()));
        }
    }

    /**
     * A Nav click: a tile picks that area; Go here asks first, then switches the editor to it.
     *
     * <p>The switch is the same {@code /dungeontrain editor <id>} Enter issues — in this world, no
     * relaunch — and it clears and re-stamps every plot, unsaved edits included. Hence the confirm,
     * and hence the plain one: {@code UnsavedCheckScreen}'s scan listed every plot rather than the
     * edited ones, which is why Enter stopped using it. Yes runs the command and closes the screen;
     * Cancel pops back to Nav with the tile still picked.</p>
     */
    private void onNavHit(EditorNavPane.Hit hit) {
        switch (hit.kind()) {
            case TILE -> EditorScreenState.setNavMode(hit.mode());
            case GO_HERE -> {
                BuilderMode mode = EditorNavPane.selected(EditorRosterClient.index());
                if (!EditorNavPane.canGo(mode, EditorRosterClient.index())) return;
                String name = Component.translatable(mode.labelKey()).getString();
                modal.open(new ConfirmScreen(EditorScreenLang.text(EditorScreenLang.NAV_CONFIRM, name),
                    EditorNavPane.switchCommand(mode)));
            }
            case NONE -> { }
        }
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
                // The walk, the note and the copy offer belonged to the build that was selected before.
                creator.onSelectionChanged();
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
                if (search.isPicking()) {
                    creditBuilder(result.creator().uuid(), result.creator().name());
                    return;
                }
                click();
                EditorCreatorBuilds.show(result.creator().uuid(), result.creator().name());
                creator.forgetLastLoad();
                browser.resetScroll();
                search.close();
            }
            case ALL -> {
                click();
                EditorCreatorBuilds.showAll();
                creator.forgetLastLoad();
                browser.resetScroll();
                search.close();
            }
            case CLEARED -> {
                click();
                EditorCreatorBuilds.clear();
                creator.forgetLastLoad();
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
                creator.forgetLastLoad();
                search.rearm();
            }
            case PICKED_ME -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    creditBuilder(player.getUUID().toString().replace("-", ""),
                        player.getGameProfile().getName());
                }
            }
            case PICKED_NONE -> creditBuilder(null, "");
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
            dispatch(EditorScreenActions.enterEntry(context(EditorRosterClient.index()), DungeonTrainNet::sendToServer));
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
            case TEST_ICON -> {
                dispatch(detail.testIcons().get(hit.index()).entry());
                return true;
            }
            case TEST_ROW -> {
                dispatchAt(detail.testRows().get(hit.index()), hit.sub());
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
            case RESEED -> {
                // Inside a test, Shift re-rolls only the template under test.
                if (games.brennan.dungeontrain.client.PortalTestSessionState.active() && Screen.hasShiftDown()) {
                    CommandRunner.run(EditorScreenActions.RESEED_FOCUS_COMMAND);
                    return true;
                }
                dispatch(detail.reseedEntry());
                return true;
            }
            case GO_HERE -> {
                if (detail.goHereEntry() == null) return false;
                dispatch(detail.goHereEntry());
                return true;
            }
            case BLOCK_GROUP -> {
                return detail.clickBlock(hit.index());
            }
            case PAGE_PREV -> {
                return detail.scrollBy(-1);
            }
            case EDIT_NOTE -> {
                detail.openNoteEditor();
                return true;
            }
            case PAGE_NEXT -> {
                return detail.scrollBy(+1);
            }
            case OLDER -> {
                creator.pageVersion(true);
                return true;
            }
            case NEWER -> {
                creator.pageVersion(false);
                return true;
            }
            default -> { return false; }
        }
    }

    /** A right-click on a sheet cell: steps a number down; anything else is left alone. */
    private boolean rightClickSheet(TemplateDataSheet.Placed placed) {
        if (placed == null || !(placed.cell().action() instanceof TemplateDataSheet.Action.Step)) return false;
        if (onSheetCell(placed)) click();
        return true;
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
        // Every editor number's gestures: click +1, shift- or right-click −1, cmd-click types. The
        // typed value goes through the inline field over the cell, as Type does.
        if (action instanceof TemplateDataSheet.Action.Step step) {
            NumberStepper.click(step.field(command -> {
                    CommandRunner.run(command);
                    afterCommand();
                }), clickButton, Screen.hasShiftDown(), MenuClickModifiers.cmdDown(),
                f -> {
                    inlineEdit.begin(step.prefix(), placed.cell().text(), placed.rect(), step.allowAll());
                    setFocused(null);
                });
            return true;
        }
        if (action instanceof TemplateDataSheet.Action.Open open) {
            modal.open(open.screen());
            return true;
        }
        if (action instanceof TemplateDataSheet.Action.ShowLoot) {
            return detail.showLootPage();
        }
        if (action instanceof TemplateDataSheet.Action.ShowBlocks) {
            return detail.showBlocksPage();
        }
        if (action instanceof TemplateDataSheet.Action.PickBuilder pick) {
            setFocused(null);   // the filter box must not eat what is typed into the panel
            search.openForPick(pick.prefix());
            return true;
        }
        return false;
    }

    /**
     * A pick in the panel's credit mode: run the sheet's {@code builder} command with the chosen
     * player and close. The roster the server re-sends afterwards redraws the sheet.
     */
    private void creditBuilder(String uuid, String name) {
        String prefix = search.pickPrefix();
        if (prefix == null) return;
        click();
        CommandRunner.run(uuid == null ? prefix + " none" : prefix + " " + uuid + " " + name);
        search.close();
        afterCommand();
    }

    private void dispatch(CommandMenuEntry entry) {
        dispatchAt(entry, 0);
    }

    private void dispatchAt(CommandMenuEntry entry, int sub) {
        if (entry == null) return;
        modal.dispatch(entry, sub, clickButton);
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
            if (onStages() && stagesPane.dropdownOpen()) {
                stagesPane.closeDropdown();
                return true;
            }
            if (!modal.pop()) this.onClose();
            return true;
        }
        // Ctrl/⌘+Z and +Y work with the screen open too — the in-game bindings stand down behind a screen.
        String history = games.brennan.dungeontrain.client.EditorUndoHotkeyClient.commandForScreenKey(keyCode, scanCode);
        if (history != null) {
            CommandRunner.run(history);
            afterCommand();
            return true;
        }
        // Ctrl/⌘+R: Test the selected template (save check first), or inside a test re-roll it.
        if (games.brennan.dungeontrain.client.EditorUndoHotkeyClient.isTestKey(keyCode, scanCode)) {
            if (games.brennan.dungeontrain.client.PortalTestSessionState.active()) {
                CommandRunner.run(EditorScreenActions.reseedNowCommand());
                afterCommand();
            } else if (detail.testEntry() != null) {
                dispatch(detail.testEntry());
            }
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

    /** What the creator-build controller needs of this screen. */
    private final class CreatorHost implements EditorCreatorLoadController.Host {
        @Override public EditorModalHost modal() { return modal; }
        @Override public void afterCommand() { EditorGuiScreen.this.afterCommand(); }
        @Override public void close() { EditorGuiScreen.this.onClose(); }
        @Override public Screen screen() { return EditorGuiScreen.this; }
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
