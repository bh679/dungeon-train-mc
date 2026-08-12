package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderOpenOptions;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.EditorTemplateLists;
import games.brennan.dungeontrain.net.BuilderDirtyRequestPacket;
import games.brennan.dungeontrain.net.BuilderNewPacket;
import games.brennan.dungeontrain.net.BuilderOpenPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Train Builder's template library: pick a kind of thing, then pick which one to work on.
 *
 * <p>The pause menu's <b>Open</b> used to lead to the four-tile mode picker, which re-stamped the
 * world into a different mode — useful, but not opening anything. There was no way to get back into
 * a template you had already made, which is most of what a builder does after the first session.</p>
 *
 * <p>The head of the screen is {@link BuilderTypeControls} — literally the same controls as New and
 * Save-as, in the same order — so the three screens read as one vocabulary rather than three. What
 * hangs underneath is the difference: New follows the type with a name field and a "start from"
 * picker, and this follows it with every template of that type as a wall of photos.</p>
 *
 * <p>Opening discards whatever is on the track, so a click here goes through the same unsaved-work
 * prompt a mode switch does — see {@link BuilderSwitchConfirmScreen}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderOpenScreen extends Screen {

    private static final int TITLE_TOP = 14;
    private static final int CONTROL_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int GRID_TOP_GAP = 8;
    private static final int BACK_BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;
    private static final int STATUS_GAP = 14;

    private static final int SCROLL_STEP = 24;

    private static final int NOTE_COLOUR = 0xA0A0A0;

    private final Screen lastScreen;

    private BuilderMode mode;
    private BuilderNewOptions.SubType subType = BuilderNewOptions.SubType.WHOLE_CARRIAGE;
    private String partKind = BuilderNewOptions.PART_KINDS.get(0);
    /**
     * The row being looked inside, empty at the top level.
     *
     * <p>Two lists have a second level and they share this one field, because from the screen's side
     * they are the same gesture: a contents group holding its sub-variants, and a Stage holding the
     * carriage variants you might build for it.</p>
     *
     * <p>A group's members are real things to open — {@code maze}'s copper room is a room — but they
     * are not siblings of {@code maze}, and listing them flat beside it (as this screen first did, and
     * as New's one-cycle-button picker still must) says they are. A grid has somewhere to go, so it
     * drills in instead.</p>
     */
    private String group = "";

    /** The ids currently on show, rebuilt whenever the type selection changes. */
    private List<String> entries = List.of();
    private BuilderTemplateGridLayout grid;
    private int scrollY;
    private boolean modeArtAvailable;

    public BuilderOpenScreen(Screen lastScreen) {
        super(Component.translatable("gui.dungeontrain.builder.open.title"));
        this.lastScreen = lastScreen;
        this.mode = BuilderMode.fromId(BuilderBoundsState.modeId()).orElse(BuilderMode.TRAIN_OUTSIDE);
    }

    @Override
    protected void init() {
        // Refresh the unsaved-work answer on the way in. The prompt below is only as good as this
        // value, and the player may have edited since the pause menu last asked.
        DungeonTrainNet.sendToServer(new BuilderDirtyRequestPacket());

        this.modeArtAvailable = BuilderTileArt.isAvailable(mode);
        this.entries = listEntries();

        int controlX = this.width / 2 - CONTROL_WIDTH / 2;
        int y = TITLE_TOP + this.font.lineHeight + ROW_GAP;

        // The mode's own art, not a template preview: the grid below is already showing every
        // template's photo, so a second picture of one of them would just compete with it.
        this.addRenderableWidget(BuilderTypeControls.mode(controlX, y, CONTROL_WIDTH, mode,
                () -> null,
                value -> {
                    mode = value;
                    group = "";
                    scrollY = 0;   // a different mode is a different list; keeping the offset would land mid-nowhere
                    rebuild();
                }));
        y += BuilderTypeControls.ART_HEIGHT + ROW_GAP;

        List<AbstractWidget> controls = new ArrayList<>();
        if (BuilderNewOptions.hasSubTypes(mode)) {
            controls.add(BuilderTypeControls.subType(controlX, y, CONTROL_WIDTH, ROW_HEIGHT, subType,
                    value -> {
                        subType = value;
                        group = "";
                        scrollY = 0;
                        rebuild();
                    }));
            // Looking inside a group: the group sits beside the sub type, sharing its row, and
            // clicking it comes back out. It reads as a second half of the same choice — "Carriage
            // Room, the Maze ones" — which is what it is.
            if (!group.isEmpty()) {
                controls.add(Button.builder(
                                Component.literal(BuilderLabels.pretty(group)),
                                b -> {
                                    group = "";
                                    scrollY = 0;
                                    rebuild();
                                })
                        .bounds(controlX, y, CONTROL_WIDTH, ROW_HEIGHT).build());
            }
            if (BuilderOpenOptions.showsPartKind(mode, subType)) {
                controls.add(BuilderTypeControls.partKind(controlX, y, CONTROL_WIDTH, ROW_HEIGHT, partKind,
                        value -> {
                            partKind = value;
                            scrollY = 0;   // each kind has its own templates
                            rebuild();
                        }));
            }
        }
        y = BuilderTypeControls.layoutRows(controls, controlX, CONTROL_WIDTH, y, ROW_HEIGHT, ROW_GAP);
        controls.forEach(this::addRenderableWidget);

        int backY = this.height - BACK_BUTTON_BOTTOM_MARGIN;
        int gridTop = y + GRID_TOP_GAP;
        int gridBottom = Math.max(gridTop, backY - STATUS_GAP);
        this.grid = BuilderTemplateGridLayout.of(this.width, gridTop, gridBottom, entries.size());
        this.scrollY = grid.clampScroll(scrollY);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .bounds((this.width - BACK_BUTTON_WIDTH) / 2, backY, BACK_BUTTON_WIDTH, ROW_HEIGHT)
                .build());
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    // ---- what the grid shows ----

    /**
     * The openable ids for the current selection.
     *
     * <p>Every list comes from {@link EditorTemplateLists}, the same call the Train Editor's own
     * template list makes — a template the Builder can't see is a template it would silently fail to
     * open.</p>
     */
    private List<String> listEntries() {
        return switch (BuilderOpenOptions.openSourceFor(mode, subType)) {
            case STAGES -> stagesThenSavedBuilds();
            case CONTENTS -> group.isEmpty()
                    ? EditorTemplateLists.contents()
                    : EditorTemplateLists.contentsMembers(group);
            // Every carriage under every stage, not the ones linked to it — see
            // BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE for why the list isn't filtered.
            case CARRIAGES_BY_STAGE -> group.isEmpty()
                    ? EditorTemplateLists.stages()
                    : EditorTemplateLists.carriages();
            case PARTS -> EditorTemplateLists.parts(partKindValue());
            case TRACK_TILES -> EditorTemplateLists.tracks();
            case TUNNEL_PORTALS -> EditorTemplateLists.tunnels(TunnelPlacer.TunnelVariant.PORTAL);
        };
    }

    /**
     * The Stage presets, then every whole carriage already saved.
     *
     * <p>Deliberately the same two lists, in the same order, that New's Whole Carriage picker shows —
     * {@code BuilderNewScreen.stagesThenSavedBuilds}. The screens ask the same question here, so
     * offering different answers would make Open a second vocabulary to learn rather than the same
     * one laid out as pictures. (It briefly listed the registered carriage <em>shells</em> instead,
     * which is a real list of things but not the one New offers.)</p>
     *
     * <p>Values are tagged, because the two halves mean different work: a Stage starts an unnamed
     * draft shaped for that stretch of the game, a saved build is loaded and named. See
     * {@link #activate}.</p>
     */
    private static List<String> stagesThenSavedBuilds() {
        List<String> out = new ArrayList<>();
        for (String id : EditorTemplateLists.stages()) {
            out.add(BuilderNewOptions.tagStage(id));
        }
        for (String id : EditorTemplateLists.wholeCarriages()) {
            out.add(BuilderNewOptions.tagWholeCarriage(id));
        }
        return out;
    }

    /**
     * The tile's caption. A saved build reads under a heading rather than as a bare name, so it
     * can't be mistaken for one more Stage the builder hasn't heard of — the same treatment
     * {@code BuilderNewScreen.pickerLabel} gives it.
     */
    private Component labelFor(BuilderOpenOptions.OpenSource source, String value) {
        String bare = BuilderOpenOptions.bareId(source, value);
        if (source == BuilderOpenOptions.OpenSource.STAGES && BuilderOpenOptions.isSavedBuild(value)) {
            return Component.translatable("gui.dungeontrain.builder.new.saved_build",
                    BuilderLabels.pretty(bare));
        }
        return Component.literal(BuilderLabels.pretty(bare));
    }

    /**
     * Whether {@code value} is a row the grid can look inside — the drill-in button's condition.
     *
     * <p>Only ever true at the top level: neither list nests twice. Which entries qualify is the
     * difference between the two sources — a contents entry has to actually be a group, while every
     * entry in the stage list is one, because a Stage's carriage variants are exactly what the arm
     * exists to show.</p>
     */
    private boolean hasSubVariants(BuilderOpenOptions.OpenSource source, String value) {
        if (!BuilderOpenOptions.drillsIn(source) || !group.isEmpty()) {
            return false;
        }
        return source != BuilderOpenOptions.OpenSource.CONTENTS
                || EditorTemplateLists.isContentsGroup(value);
    }

    private CarriagePartKind partKindValue() {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(partKind)) {
                return kind;
            }
        }
        return CarriagePartKind.values()[0];
    }

    // ---- render ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_TOP, 0xFFFFFF);

        BuilderOpenOptions.OpenSource source = BuilderOpenOptions.openSourceFor(mode, subType);
        boolean openable = BuilderOpenOptions.isOpenable(source);
        CarriagePartKind kind = source == BuilderOpenOptions.OpenSource.PARTS ? partKindValue() : null;

        if (!entries.isEmpty()) {
            // Scissored: a scrolled cell must not bleed over the type controls or the Back button.
            g.enableScissor(0, grid.topY(), this.width, grid.bottomY());
            for (int i = 0; i < entries.size(); i++) {
                if (!grid.isVisible(i, scrollY)) {
                    continue;
                }
                int x = grid.xFor(i);
                int y = grid.yFor(i, scrollY);
                boolean hovered = mouseX >= x && mouseX < x + grid.cellWidth()
                        && mouseY >= y && mouseY < y + grid.cellHeight()
                        && mouseY >= grid.topY() && mouseY < grid.bottomY();
                String value = entries.get(i);
                BuilderTemplateTile.render(g, mode, modeArtAvailable,
                        BuilderOpenOptions.photoKindFor(source, value, !group.isEmpty()),
                        BuilderOpenOptions.bareId(source, value), kind, labelFor(source, value),
                        x, y, grid.cellWidth(), grid.cellHeight(), hovered, openable);
                if (hasSubVariants(source, value)) {
                    BuilderTemplateTile.renderMore(g, grid.moreX(i), grid.moreY(i, scrollY),
                            grid.moreSize(), grid.isOverMore(i, mouseX, mouseY, scrollY));
                }
            }
            g.disableScissor();
        }

        Component note = statusNote(openable);
        if (note != null) {
            g.drawCenteredString(this.font, note, this.width / 2,
                    this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP + 2, NOTE_COLOUR);
        }
    }

    /** The line under the grid: why it's empty, or why you can look but not touch. */
    private Component statusNote(boolean openable) {
        if (!openable) {
            return Component.translatable("gui.dungeontrain.builder.open.not_buildable");
        }
        return entries.isEmpty() ? Component.translatable("gui.dungeontrain.builder.open.empty") : null;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !entries.isEmpty()) {
            BuilderOpenOptions.OpenSource source = BuilderOpenOptions.openSourceFor(mode, subType);

            // The drill-in button first: it sits inside a cell, so testing the cell first would
            // swallow every click on it and open the group's own template instead.
            for (int i = 0; i < entries.size(); i++) {
                String value = entries.get(i);
                if (hasSubVariants(source, value) && grid.isOverMore(i, mouseX, mouseY, scrollY)) {
                    group = value;
                    scrollY = 0;
                    rebuild();
                    return true;
                }
            }

            // A Stage in the carriage list is a folder, not a leaf: it names a stretch of the game,
            // and the templates are the carriages underneath it. So the whole tile drills in and the
            // chevron is only the affordance saying so — clicking the picture of a stage and getting
            // a brand-new whole carriage instead is not a thing anyone was asking for.
            //
            // Not the same for CONTENTS, where a group tile is itself a real template: `maze` is a
            // room you can open as well as a folder you can look inside, so there the two clicks
            // genuinely differ and the chevron has to be aimed at.
            if (source == BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE && group.isEmpty()) {
                int index = grid.indexAt(mouseX, mouseY, scrollY, entries.size());
                if (index >= 0) {
                    group = entries.get(index);
                    scrollY = 0;
                    rebuild();
                    return true;
                }
            }

            if (BuilderOpenOptions.isOpenable(source)) {
                int index = grid.indexAt(mouseX, mouseY, scrollY, entries.size());
                if (index >= 0) {
                    activate(entries.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double deltaY) {
        if (grid != null && grid.maxScroll() > 0) {
            this.scrollY = grid.clampScroll(this.scrollY - (int) (deltaY * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, deltaY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    /**
     * Act on a clicked tile, asking first if there is work on the track it would throw away.
     *
     * <p>The prompt is the one a mode switch raises, and for the same reason — either branch below
     * re-stamps the train. Once it has been answered the request goes as {@code force}, because the
     * question the server would otherwise ask has just been answered by the player.</p>
     */
    private void activate(String value) {
        BuilderOpenOptions.OpenSource source = BuilderOpenOptions.openSourceFor(mode, subType);
        Runnable action = actionFor(source, value, true);
        if (action == null) {
            return;
        }
        if (BuilderDirtyState.hasUnsavedChanges()) {
            Minecraft.getInstance().setScreen(new BuilderSwitchConfirmScreen(this,
                    labelFor(source, value), BuilderDirtyState.dirtyCount(), action));
            return;
        }
        Minecraft.getInstance().setScreen(null);
        Runnable unforced = actionFor(source, value, false);
        if (unforced != null) {
            unforced.run();
        }
    }

    /**
     * What a tile does, or null when it names nothing actionable.
     *
     * <p>The Whole Carriage list is the only one with two answers, and the difference matters:</p>
     * <ul>
     *   <li>A <b>saved build</b> is <em>opened</em> — loaded, and named, so Save writes back to it.
     *       That is {@code BuilderOpenPacket}, whose whole contract is to resolve the template before
     *       touching the world.</li>
     *   <li>A <b>Stage</b> is not a template and has no file to load. Clicking it means "a fresh
     *       carriage for that stretch of the game", which is exactly what New does with the same
     *       pick — so it sends {@code BuilderNewPacket} with an <b>empty name</b>. The empty name is
     *       load-bearing: it leaves the result an unnamed draft, so New's lenient fallbacks can't
     *       reach a Save that overwrites something.</li>
     * </ul>
     *
     * <p>The stage list under Carriage Room only ever reaches here one level in, where every tile is
     * a carriage template and is opened. Its top-level Stage tiles are navigation — see
     * {@link #mouseClicked} — because a Stage there has carriages underneath it to get to, which is
     * a better answer to clicking one than silently starting an unrelated new build.</p>
     */
    private Runnable actionFor(BuilderOpenOptions.OpenSource source, String value, boolean force) {
        // Only the Whole Carriage list, where a Stage is a leaf and starting a fresh carriage for
        // that stretch of the game is the only thing clicking it could mean. In the carriage list a
        // Stage is a folder and never reaches here — mouseClicked drills into it instead.
        boolean stageTile = source == BuilderOpenOptions.OpenSource.STAGES
                && !BuilderOpenOptions.isSavedBuild(value);
        if (stageTile) {
            String stageId = BuilderOpenOptions.bareId(source, value);
            // Whole Carriage whichever arm the stage was clicked in: "a fresh carriage for that
            // stretch of the game" is one action, and it is the only reading BuilderNewPacket has
            // for a tagged stage — the Carriage Room arm's own sub type would send the id into
            // CopySource.CARRIAGES, where a stage name resolves to no carriage at all.
            return () -> DungeonTrainNet.sendToServer(new BuilderNewPacket(
                    mode.id(), BuilderNewOptions.SubType.WHOLE_CARRIAGE.id(), partKind,
                    BuilderNewOptions.tagStage(stageId), ""));
        }
        // Drilled into a stage: a carriage template, opened as one, dressed in the stage being
        // browsed. forSelection reads Carriage Room as a room — true from inside the wall, not here.
        if (source == BuilderOpenOptions.OpenSource.CARRIAGES_BY_STAGE) {
            String carriageId = BuilderOpenOptions.bareId(source, value);
            String stageId = group;
            // The sub type rides along because the store this came out of can't imply it: a carriage
            // browsed under a Stage is a carriage template, so Save must write it as a whole
            // carriage, but it was opened as one room-sized build and stands alone on the track.
            return () -> DungeonTrainNet.sendToServer(new BuilderOpenPacket(mode.id(),
                    BuilderPhotoPaths.Kind.CARRIAGE.id(), carriageId, "", force, stageId,
                    subType.id()));
        }
        BuilderOpenRequest request = BuilderOpenRequest
                .forSelection(subType, BuilderOpenOptions.bareId(source, value), partKindValue())
                .orElse(null);
        if (request == null) {
            return null;
        }
        return () -> DungeonTrainNet.sendToServer(new BuilderOpenPacket(mode.id(),
                request.kind().id(), request.id(), request.partKindId(), force, "", subType.id()));
    }
}
