package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.BuilderProfileScreen;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menu shown when the player is inside an editor plot.
 *
 * <p>Rows are filed under four tabs — see {@link EditorMenuTab} for what belongs where and
 * why. The strip is row 0; the chosen tab's rows follow. A tab with no rows for the current
 * category is hidden rather than shown empty, so an {@code architecture} plot gets
 * File | Settings | Nav and the strip re-splits across the three.</p>
 *
 * <p>DevMode is a live toggle driven by {@link EditorStatusHudOverlay} and is only surfaced on
 * non-{@code main} builds (see {@link #shouldShowDevModeToggle(String)} — release jars built
 * from {@code main} hide the row entirely).</p>
 *
 * <p>Unlike the previous flat list, {@code parts} is no longer a separate early-return branch.
 * It was a near-duplicate that had already drifted (Undo/Redo sat in a different position in
 * each), so it now flows through the same builders and differs only where it genuinely must:
 * its save and reset route through the part-aware {@code /editor part} subcommands, because
 * {@code dungeontrain save} dispatches via {@code EditorCategory.locate}, which cannot see part
 * plots.</p>
 */
public final class EditorMenuScreen implements MenuScreen {

    /**
     * Panel width while the Walls row is showing, in world units.
     *
     * <p>Sized for the longest mode label, "Walls: Endless Repetition", at
     * {@link CommandMenuLayout#TEXT_SCALE} — the shared default fits about fifteen characters and
     * that is twenty-five. A constant rather than a measurement because the row builders have no
     * {@code Font} to hand, and the set of modes is fixed and small.</p>
     */
    private static final double WALLS_ROW_PANEL_WIDTH = 2.6;

    /**
     * Panel width while the Copies Block row is showing.
     *
     * <p>The same width the Walls row asks for. The row named a block id when it was added, which
     * needed more than forty characters; it names the gesture now — {@code Blocks: + held} — and the
     * palette itself is shown as icons on the plot panel, so there is nothing here to size for.</p>
     */
    private static final double COPIES_BLOCK_ROW_PANEL_WIDTH = WALLS_ROW_PANEL_WIDTH;

    @Override public String title() { return "Editor"; }

    /**
     * The tab strip is row 0 and stays pinned while the rest of the list scrolls — a tall Current
     * tab would otherwise scroll away the only control that gets you out of it.
     */
    @Override public int stickyRows() { return 1; }

    /**
     * A snapshot of everything the row builders need, read once per rebuild.
     *
     * <p>{@code model} is the friendly path string (HUD-style, may contain "/").
     * {@code modelId} is the bare command-token id used to dispatch {@code /dt editor ...}.
     * {@code modelName} is the trailing variant-name segment — for track-side models the path
     * string is e.g. "track / track2" and the bare name is "track2". For carriages and contents
     * modelId/modelName are identical to model. <b>Only modelId/modelName are safe to splice into
     * a command string.</b></p>
     */
    private record Ctx(String category, String model, String modelId, String modelName, int weight) {

        static Ctx read() {
            return new Ctx(
                EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT),
                EditorStatusHudOverlay.model(),
                EditorStatusHudOverlay.modelId(),
                EditorStatusHudOverlay.modelName(),
                EditorStatusHudOverlay.weight());
        }

        boolean isParts()   { return "parts".equals(category); }
        boolean isPortals() { return "portals".equals(category); }
    }

    @Override public List<CommandMenuEntry> entries() {
        Ctx ctx = Ctx.read();
        Map<EditorMenuTab, List<CommandMenuEntry>> byTab = rowsByTab(ctx);

        List<EditorMenuTab> visible = visibleTabs(byTab);
        if (visible.isEmpty()) return List.of();

        EditorMenuTab active = EditorMenuTab.resolve(visible);

        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(tabStrip(visible, active));
        out.addAll(byTab.get(active));
        return out;
    }

    /** Tabs that have at least one row, in strip order. Empty tabs are hidden, not shown blank. */
    static List<EditorMenuTab> visibleTabs(Map<EditorMenuTab, List<CommandMenuEntry>> byTab) {
        List<EditorMenuTab> visible = new ArrayList<>();
        for (EditorMenuTab tab : EditorMenuTab.values()) {
            List<CommandMenuEntry> rows = byTab.get(tab);
            if (rows != null && !rows.isEmpty()) visible.add(tab);
        }
        return visible;
    }

    /**
     * Build every tab's rows for an explicit context. Package-private so the unit test can assert
     * row placement per category without standing up the client HUD state.
     */
    static Map<EditorMenuTab, List<CommandMenuEntry>> rowsByTab(
        String category, String model, String modelId, String modelName, int weight
    ) {
        return rowsByTab(new Ctx(category, model, modelId, modelName, weight));
    }

    private static Map<EditorMenuTab, List<CommandMenuEntry>> rowsByTab(Ctx ctx) {
        Map<EditorMenuTab, List<CommandMenuEntry>> byTab = new EnumMap<>(EditorMenuTab.class);
        byTab.put(EditorMenuTab.FILE, fileRows(ctx));
        byTab.put(EditorMenuTab.CURRENT, currentRows(ctx));
        byTab.put(EditorMenuTab.SETTINGS, settingsRows(ctx));
        byTab.put(EditorMenuTab.NAV, navRows(ctx));
        return byTab;
    }

    /**
     * The strip itself, as a Split / Triple / Quad depending on how many tabs survive.
     *
     * <p>Each cell is a {@link CommandMenuEntry.ClientAction}: switching tab is pure client state,
     * so there is no command to run and no server round-trip to wait for. ClientAction leaves the
     * menu open, and the next rebuild renders the newly chosen tab.</p>
     */
    private static CommandMenuEntry tabStrip(List<EditorMenuTab> visible, EditorMenuTab active) {
        List<CommandMenuEntry> cells = new ArrayList<>();
        for (EditorMenuTab tab : visible) {
            cells.add(new CommandMenuEntry.ClientAction(
                tab.label(),
                () -> EditorMenuTab.select(tab),
                tab == active));
        }
        return switch (cells.size()) {
            case 1 -> cells.get(0);
            case 2 -> new CommandMenuEntry.Split(cells.get(0), cells.get(1), 0.50);
            case 3 -> new CommandMenuEntry.Triple(
                cells.get(0), cells.get(1), cells.get(2), 1.0 / 3.0, 2.0 / 3.0);
            default -> new CommandMenuEntry.Quad(
                cells.get(0), cells.get(1), cells.get(2), cells.get(3), 0.25, 0.50, 0.75);
        };
    }

    // ------------------------------------------------------------------
    // File — the template as a file
    // ------------------------------------------------------------------

    /**
     * Reads top-to-bottom as a lifecycle: make one, see what you have made, save it, name it,
     * step back, destroy it, ship it. Undo/Redo divides the safe half from the destructive half.
     */
    private static List<CommandMenuEntry> fileRows(Ctx ctx) {
        List<CommandMenuEntry> out = new ArrayList<>();

        if (ctx.isParts()) {
            // Parts address a model as "kind:name" rather than a bare id, so New/Remove are built
            // from that split rather than the category tables the other categories use.
            String[] kindName = partsKindName(ctx.model());
            if (kindName != null) {
                out.add(new CommandMenuEntry.Split(
                    new CommandMenuEntry.DrillIn("New",
                        new NewSourcePickerScreen(NewSourcePickerScreen.Category.PARTS,
                            kindName[0], kindName[1])),
                    new CommandMenuEntry.DrillIn("Remove",
                        new ConfirmScreen("Remove '" + ctx.model() + "'?",
                            "dungeontrain editor part reset " + kindName[0] + " " + kindName[1])),
                    0.50));
            }
        } else {
            // New / Remove — only meaningful for categories whose models are user-authorable
            // (carriages, contents) or whose registry supports deletion (tracks, portals). For
            // architecture the concept doesn't apply, so the row is omitted rather than showing
            // buttons that error on click.
            CommandMenuEntry newEntry = newEntryFor(ctx.category(), ctx.modelId(), ctx.model());
            CommandMenuEntry removeEntry = removeEntryFor(ctx.category(), ctx.modelId(), ctx.model());
            if (newEntry != null && removeEntry != null) {
                out.add(new CommandMenuEntry.Split(newEntry, removeEntry, 0.50));
            }
        }

        out.add(myBuildsEntry());

        // Parts have their own Save — `dungeontrain save` dispatches via EditorCategory.locate,
        // which doesn't see part plots.
        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.Run("Save",
                ctx.isParts() ? "dungeontrain editor part save" : "dungeontrain save"),
            new CommandMenuEntry.Run("All",
                ctx.isParts() ? "dungeontrain editor part save all" : "dungeontrain save all"),
            0.80));

        CommandMenuEntry rename = renameEntryFor(ctx);
        if (rename != null) out.add(rename);

        // Undo | Redo — steps the per-plot editor history. Mirrors the Ctrl/Cmd+Z / Ctrl/Cmd+Y
        // keybindings through the same commands, so the two surfaces cannot drift apart.
        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.Run("Undo", "dungeontrain editor undo"),
            new CommandMenuEntry.Run("Redo", "dungeontrain editor redo"),
            0.50));

        // Reset | Clear — paired destructive actions. Reset deletes the on-disk template; Clear
        // wipes interior blocks to air. Parts have no Reset, and the categories without a Clear
        // (tracks / architecture) fall back to a solo Reset.
        CommandMenuEntry clear = clearEntryFor(ctx.category(), ctx.model());
        if (ctx.isParts()) {
            if (clear != null) out.add(clear);
        } else {
            CommandMenuEntry reset = new CommandMenuEntry.Run("Reset", "dungeontrain reset");
            out.add(clear != null ? new CommandMenuEntry.Split(reset, clear, 0.50) : reset);
        }

        out.add(new CommandMenuEntry.DrillIn("Package", new PackageListScreen()));
        return out;
    }

    /** Split a parts model ("wheelset:heavy") into kind and name, or null when it isn't one. */
    private static String[] partsKindName(String model) {
        if (model == null) return null;
        int sep = model.indexOf(':');
        if (sep <= 0 || sep >= model.length() - 1) return null;
        return new String[] { model.substring(0, sep), model.substring(sep + 1) };
    }

    // ------------------------------------------------------------------
    // Current — properties of the model being stood in
    // ------------------------------------------------------------------

    private static List<CommandMenuEntry> currentRows(Ctx ctx) {
        List<CommandMenuEntry> out = new ArrayList<>();

        // Contents — drilldown listing every registered content with a per-row red/green toggle so
        // the author can exclude specific contents from this carriage's spawn pool. Only shown when
        // a concrete variant id is in scope.
        if ("carriages".equals(ctx.category()) && notEmpty(ctx.modelId())) {
            out.add(new CommandMenuEntry.DrillIn("Contents",
                CarriageContentsAllowScreen.forCarriage(ctx.modelId())));
        }

        // The same drilldown for a portal room, but only while its Contents setting is on — with it
        // Off the room draws nothing and the toggles would steer an empty pool. Addressed by room
        // NAME, not modelId: modelId is the kind tag "portal_room", shared by every room.
        if (ctx.isPortals() && notEmpty(ctx.modelName())
            && PortalRoomSettings.parse(EditorStatusHudOverlay.roomMode()).contents().furnishes()) {
            out.add(new CommandMenuEntry.DrillIn("Contents",
                CarriageContentsAllowScreen.forPortalRoom(ctx.modelName())));
        }

        // Weight — Triple row: [-] / Weight (N) / [+] for every category that has a weight pool.
        // Side cells nudge by 1 server-side and stay open so the player can tap-tap-tap; middle
        // cell drops into typing mode for an exact value.
        CommandMenuEntry weightRow =
            weightTripleFor(ctx.category(), ctx.modelId(), ctx.modelName(), ctx.weight());
        if (weightRow != null) out.add(weightRow);

        if (ctx.isPortals()) out.addAll(portalRows());

        // Spawn gate — gated on exactly the same condition as Weight (weighted, addressable
        // models). When the model is linked to a Stage the editable cells are hidden and only the
        // Stage chip shows; to change the gate the player edits the Stage or picks Custom.
        if (weightRow != null) out.addAll(spawnGateRows(ctx));

        return out;
    }

    /** The portal-room block, in the order the settings read: box, then walls, then contents. */
    private static List<CommandMenuEntry> portalRows() {
        String mode = EditorStatusHudOverlay.roomMode();
        List<CommandMenuEntry> out = new ArrayList<>();

        // Size — a portal room is the one plot whose box the author chooses: length outright (it is
        // the distance walked underneath a portal, not a footprint) and width and height above the
        // floor the corridor mouth sets.
        addIfPresent(out, EditorMenuPortalRows.sizeTripleFor(
            "length", "Length", EditorStatusHudOverlay.roomLength()));
        addIfPresent(out, EditorMenuPortalRows.sizeTripleFor(
            "width", "Width", EditorStatusHudOverlay.roomWidth()));
        addIfPresent(out, EditorMenuPortalRows.sizeTripleFor(
            "height", "Height", EditorStatusHudOverlay.roomHeight()));

        addIfPresent(out, EditorMenuPortalRows.wallsModeRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.copiesRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.copiesBlockRowFor(
            mode, PortalRoomCopiesVariant.Plane.FLOOR));
        addIfPresent(out, EditorMenuPortalRows.copiesBlockRowFor(
            mode, PortalRoomCopiesVariant.Plane.ROOF));
        addIfPresent(out, EditorMenuPortalRows.doorWallRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.roomContentsRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.roomBooksRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.roomSkyRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.exitsRowFor(mode));
        addIfPresent(out, EditorMenuPortalRows.exitEveryTripleFor(mode));
        addIfPresent(out, EditorMenuPortalRows.exitMoveTripleFor(mode));

        return out;
    }

    /**
     * The four spawn-gate rows, or the single Stage chip that replaces them.
     *
     * <p>They live together in Current — and the chip with them — because a linked Stage collapses
     * Min Lv, Max Lv and Phases into itself. Splitting the group across tabs would mean Current
     * silently losing rows whenever an author linked a Stage.</p>
     */
    private static List<CommandMenuEntry> spawnGateRows(Ctx ctx) {
        String stageId = EditorStatusHudOverlay.stageId();
        if (notEmpty(stageId)) {
            return List.of(new CommandMenuEntry.DrillIn(
                "Stage: " + stageId + "  ▾",
                new StagePickerScreen(ctx.category(), ctx.modelId(), ctx.modelName(), stageId)));
        }

        List<CommandMenuEntry> out = new ArrayList<>();
        int minLv = EditorStatusHudOverlay.minLevel();
        int maxLv = EditorStatusHudOverlay.maxLevel();
        addIfPresent(out, levelTripleFor(ctx.category(), ctx.modelId(), ctx.modelName(),
            "minlevel", "Min Lv (" + minLv + ")", "0-1000"));
        addIfPresent(out, levelTripleFor(ctx.category(), ctx.modelId(), ctx.modelName(),
            "maxlevel", "Max Lv (" + (maxLv < 0 ? "all" : Integer.toString(maxLv)) + ")",
            "-1..1000"));
        out.add(new CommandMenuEntry.DrillIn("Phases",
            new PhaseSelectScreen(ctx.category(), ctx.modelId(), ctx.modelName())));
        // Stage / Custom picker — link this template to a Stage preset (or stay Custom).
        out.add(new CommandMenuEntry.DrillIn("Stage: Custom  ▾",
            new StagePickerScreen(ctx.category(), ctx.modelId(), ctx.modelName(), "")));
        return out;
    }

    // ------------------------------------------------------------------
    // Settings — editor-wide preferences, outliving any one model
    // ------------------------------------------------------------------

    private static List<CommandMenuEntry> settingsRows(Ctx ctx) {
        List<CommandMenuEntry> out = new ArrayList<>();

        if (shouldShowDevModeToggle(VersionInfo.BRANCH)) {
            out.add(new CommandMenuEntry.Toggle(
                "DevMode", EditorStatusHudOverlay.isDevModeOn(),
                "dungeontrain editor devmode on",
                "dungeontrain editor devmode off"));
        }

        // Editor Menus — master toggle for the world-space editor menus: drives the auto-opening
        // part-position menu's persistent flag and, when turned off, also closes any open
        // tap-to-open block-variant / container-contents menus. Available in every template
        // category — the flag it reads is a per-player state, not scoped to carriages.
        out.add(new CommandMenuEntry.Toggle(
            "Editor Menus", EditorStatusHudOverlay.isEditorMenusVisible(),
            "dungeontrain editor editormenus on",
            "dungeontrain editor editormenus off"));

        // Mirror — author one octant, the editor mirrors live (and rebuilds on save) across the
        // enabled axes. Available in every template plot with a model in scope; architecture has
        // no model to mirror.
        if (ctx.isParts()
            || (("carriages".equals(ctx.category()) || "contents".equals(ctx.category())
                 || "tracks".equals(ctx.category()) || ctx.isPortals())
                && notEmpty(ctx.modelName()))) {
            addMirrorToggles(out);
        }

        if (!ctx.isParts()) {
            out.add(new CommandMenuEntry.DrillIn("Stages", new StagesListScreen()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Nav — in, around, out
    // ------------------------------------------------------------------

    private static List<CommandMenuEntry> navRows(Ctx ctx) {
        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(new CommandMenuEntry.DrillIn("Enter", new EnterCategoryMenuScreen()));

        // Go and stand in one. Portals only, because that is the only category where "the carriage"
        // names something you can walk into. It stamps the room the player is standing in — under
        // the world, corridor each side, no train — so there is nothing to pick and no save prompt
        // to answer.
        if (ctx.isPortals()) {
            out.add(new CommandMenuEntry.Run("Test the Carriage", "dungeontrain portal test"));
        }

        // Exit unwinds the active editor session, clears the editor plots, and teleports the player
        // back to where they entered the editor from.
        out.add(new CommandMenuEntry.Run("Exit", "dungeontrain editor exit"));
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private static void addIfPresent(List<CommandMenuEntry> out, CommandMenuEntry entry) {
        if (entry != null) out.add(entry);
    }

    /**
     * Append a {@code Mirror} header label followed by a {@code [X | Y | Z | V]} toggle row, wired
     * to the position-resolved {@code editor mirror <axis> on|off} command (resolves whichever plot
     * the player stands in). X/Y/Z mirror structural blocks across an axis; the {@code V} toggle
     * additionally mirrors the per-cell variant pools (opt-in — off by default). Toggle state (and
     * the green on-tint) is the server-pushed {@link EditorStatusHudOverlay} mirror flags.
     *
     * <p>The trailing {@code Rebuild} row runs {@code editor mirror rebuild}, which re-mirrors the
     * plot from its master octant on demand — saving no longer does that implicitly.</p>
     */
    private static void addMirrorToggles(List<CommandMenuEntry> out) {
        out.add(new CommandMenuEntry.Label("Mirror"));
        // showStateText=false → state shown by the green (on) / grey (off) tint only.
        CommandMenuEntry x = new CommandMenuEntry.Toggle("X", EditorStatusHudOverlay.mirrorX(),
            "dungeontrain editor mirror x on", "dungeontrain editor mirror x off", false);
        CommandMenuEntry y = new CommandMenuEntry.Toggle("Y", EditorStatusHudOverlay.mirrorY(),
            "dungeontrain editor mirror y on", "dungeontrain editor mirror y off", false);
        CommandMenuEntry z = new CommandMenuEntry.Toggle("Z", EditorStatusHudOverlay.mirrorZ(),
            "dungeontrain editor mirror z on", "dungeontrain editor mirror z off", false);
        CommandMenuEntry v = new CommandMenuEntry.Toggle("V", EditorStatusHudOverlay.mirrorVariants(),
            "dungeontrain editor mirror v on", "dungeontrain editor mirror v off", false);
        out.add(new CommandMenuEntry.Quad(x, y, z, v, 0.25, 0.50, 0.75));
        // Explicit re-mirror. Saving no longer rebuilds the far half from the master octant, so
        // this is the (deliberate) way to force it.
        out.add(new CommandMenuEntry.Run("Rebuild", "dungeontrain editor mirror rebuild"));
    }

    /**
     * Returns {@code true} when the DevMode toggle row should be added to the editor menu. Hidden on
     * release builds (jar built from {@code main}); any other branch — feature branches,
     * detached-HEAD short SHAs, the {@code "?"} fallback when git detection failed at build time, or
     * {@code null} — shows the toggle so devs aren't locked out when build metadata is missing.
     * Extracted as a pure predicate so the unit test can pin behavior without having to mutate
     * {@link VersionInfo}'s static initializer.
     */
    static boolean shouldShowDevModeToggle(String branch) {
        return !"main".equals(branch);
    }

    /**
     * Build a {@link CommandMenuEntry.Triple} for the active model's weight, or null when the
     * category has no weight pool / no addressable model. Extracted so the unit test can pin command
     * strings without standing up the full menu.
     *
     * <p>Command shapes:
     * <ul>
     *   <li>{@code carriages}: {@code dungeontrain editor weight <modelId> {dec|inc|""}}</li>
     *   <li>{@code tracks}: {@code dungeontrain editor tracks weight <modelId> <modelName> {dec|inc|""}}</li>
     *   <li>{@code contents}: {@code dungeontrain editor contents weight <modelId> {dec|inc|""}}</li>
     * </ul>
     *
     * <p>{@code modelId} (not {@code model}) is spliced into commands so track-side models with
     * friendly path strings ({@code "track / track2"}) don't break the parser.</p>
     */
    static CommandMenuEntry weightTripleFor(String category, String modelId, String modelName, int currentWeight) {
        if (modelId == null || modelId.isEmpty()) return null;
        String prefix;
        switch (category) {
            case "carriages" -> prefix = "dungeontrain editor weight " + modelId;
            case "tracks" -> {
                if (modelName == null || modelName.isEmpty()) return null;
                prefix = "dungeontrain editor tracks weight " + modelId + " " + modelName;
            }
            case "portals" -> {
                if (modelName == null || modelName.isEmpty()) return null;
                prefix = "dungeontrain editor portals weight " + modelId + " " + modelName;
            }
            case "contents" -> prefix = "dungeontrain editor contents weight " + modelId;
            default -> { return null; }
        }
        String label = currentWeight >= 0 ? "Weight (" + currentWeight + ")" : "Weight";
        CommandMenuEntry minus  = new CommandMenuEntry.Stay("-", prefix + " dec");
        CommandMenuEntry weight = new CommandMenuEntry.TypeArg(label, "0-100", prefix);
        CommandMenuEntry plus   = new CommandMenuEntry.Stay("+", prefix + " inc");
        return new CommandMenuEntry.Triple(minus, weight, plus, 0.10, 0.90);
    }

    /**
     * Wider than the shared default while a Walls row is showing.
     *
     * <p>{@link CommandMenuLayout#PANEL_WIDTH} fits about fifteen characters, which covered every
     * row this menu had until "Walls: Endless Repetition" — twenty-five — ran off both edges. The
     * wide rows only exist in Current, so the panel widens only while that tab is the one showing;
     * every other tab, and every other menu in the game, keeps the width it was tuned at.</p>
     */
    @Override
    public double panelWidth() {
        if (EditorMenuTab.active() != EditorMenuTab.CURRENT) {
            return CommandMenuLayout.PANEL_WIDTH;
        }
        String mode = EditorStatusHudOverlay.roomMode();
        if (mode == null || EditorStatusPacket.NO_MODE.equals(mode)) {
            return CommandMenuLayout.PANEL_WIDTH;
        }
        double widest = EditorPlotLabelsRenderer.hasCopiesBlockRowFor(mode)
            ? Math.max(WALLS_ROW_PANEL_WIDTH, COPIES_BLOCK_ROW_PANEL_WIDTH)
            : WALLS_ROW_PANEL_WIDTH;
        return Math.max(CommandMenuLayout.PANEL_WIDTH, widest);
    }

    /**
     * Build a {@link CommandMenuEntry.Triple} stepper for a per-template spawn-gate level bound,
     * or null when the category has no gate / no addressable model. {@code sub} is the gate
     * subcommand ({@code minlevel} / {@code maxlevel}); {@code label} is the pre-rendered cell
     * label (caller formats the current value); {@code hint} is the typing-mode placeholder.
     * Command shapes mirror {@link #weightTripleFor}.
     */
    static CommandMenuEntry levelTripleFor(String category, String modelId, String modelName,
                                           String sub, String label, String hint) {
        if (modelId == null || modelId.isEmpty()) return null;
        String prefix;
        switch (category) {
            case "carriages" -> prefix = "dungeontrain editor " + sub + " " + modelId;
            case "tracks" -> {
                if (modelName == null || modelName.isEmpty()) return null;
                prefix = "dungeontrain editor tracks " + sub + " " + modelId + " " + modelName;
            }
            case "portals" -> {
                if (modelName == null || modelName.isEmpty()) return null;
                prefix = "dungeontrain editor portals " + sub + " " + modelId + " " + modelName;
            }
            case "contents" -> prefix = "dungeontrain editor contents " + sub + " " + modelId;
            default -> { return null; }
        }
        CommandMenuEntry minus  = new CommandMenuEntry.Stay("-", prefix + " dec");
        CommandMenuEntry middle = new CommandMenuEntry.TypeArg(label, hint, prefix);
        CommandMenuEntry plus   = new CommandMenuEntry.Stay("+", prefix + " inc");
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }

    /**
     * "New" drills into a {@link NewSourcePickerScreen} for carriages and contents (Blank / Current
     * / Standard seed picker before naming). For {@code tracks} the {@code modelId} is the kind tag
     * the player is standing on ({@code track}, {@code pillar_top}, {@code tunnel_section},
     * {@code adjunct_stairs}, ...) — passed to {@code /dt editor tracks new <kind> <typed-name>},
     * which clones the variant the player is currently standing on under the new name and teleports
     * them to the new plot. Returns null for categories that don't support author-authored new
     * models.
     */
    static CommandMenuEntry newEntryFor(String category, String modelId, String model) {
        return switch (category) {
            case "carriages" -> new CommandMenuEntry.DrillIn(
                "New",
                new NewSourcePickerScreen(
                    NewSourcePickerScreen.Category.CARRIAGES, null, modelId));
            case "contents" -> new CommandMenuEntry.DrillIn(
                "New",
                new NewSourcePickerScreen(
                    NewSourcePickerScreen.Category.CONTENTS, null, modelId));
            case "tracks" -> {
                if (modelId == null || modelId.isEmpty()) yield null;
                yield new CommandMenuEntry.TypeArg(
                    "New", "name",
                    "dungeontrain editor tracks new " + modelId);
            }
            case "portals" -> {
                if (modelId == null || modelId.isEmpty()) yield null;
                yield new CommandMenuEntry.TypeArg(
                    "New", "name",
                    "dungeontrain editor portals new " + modelId);
            }
            default -> null;
        };
    }

    /**
     * "Remove" deletes the current model's config-dir file via {@code /dt editor reset <id>} /
     * {@code /dt editor contents reset <id>}. Drills into a ConfirmScreen first so mis-clicks don't
     * silently wipe the user's work.
     *
     * <p>For {@code tracks} the menu drills into a confirm that fires
     * {@code /dt editor tracks reset <kind>} — that command no-ops with a friendly error when the
     * active variant is the synthetic {@code default} (you can't remove the built-in fallback).</p>
     *
     * <p>{@code modelId} is what gets spliced into the command (must be a single command token);
     * {@code model} is the friendly path used in the confirm prompt label.</p>
     */
    static CommandMenuEntry removeEntryFor(String category, String modelId, String model) {
        if (modelId == null || modelId.isEmpty()) return null;
        return switch (category) {
            case "carriages" -> new CommandMenuEntry.DrillIn(
                "Remove",
                new ConfirmScreen("Remove '" + model + "'?",
                    "dungeontrain editor reset " + modelId));
            case "contents" -> new CommandMenuEntry.DrillIn(
                "Remove",
                new ConfirmScreen("Remove '" + model + "'?",
                    "dungeontrain editor contents reset " + modelId));
            case "tracks" -> new CommandMenuEntry.DrillIn(
                "Remove",
                new ConfirmScreen("Remove the current variant for '" + model + "'?",
                    "dungeontrain editor tracks reset " + modelId));
            case "portals" -> new CommandMenuEntry.DrillIn(
                "Remove",
                new ConfirmScreen("Remove the current variant for '" + model + "'?",
                    "dungeontrain editor portals reset " + modelId));
            default -> null;
        };
    }

    /**
     * "Clear" wipes every interior block of the current plot to air via {@code /dt editor clear}.
     * Drills into a ConfirmScreen first since the action is destructive — same gating as Remove.
     * Returns null for categories without a single addressable model id (tracks, pillars, tunnels,
     * architecture).
     */
    private static CommandMenuEntry clearEntryFor(String category, String model) {
        if (model == null || model.isEmpty()) return null;
        return switch (category) {
            case "carriages", "contents", "parts", "portals" -> new CommandMenuEntry.DrillIn(
                "Clear",
                new ConfirmScreen("Clear all blocks in '" + model + "'?",
                    "dungeontrain editor clear"));
            default -> null;
        };
    }

    /**
     * "Rename" pre-fills the typing field with the current model name and on submit runs the
     * category's rename subcommand — carriages and contents implement {@code save <new_name>} as a
     * true rename (saveAs: delete-old + write-new + registry update), and parts have their own
     * {@code part rename}. Returns null for builtin variants and for categories that don't support
     * author-authored renames.
     */
    private static CommandMenuEntry renameEntryFor(Ctx ctx) {
        String model = ctx.model();
        if (model == null || model.isEmpty()) return null;
        if (ctx.isParts()) {
            String[] kindName = partsKindName(model);
            if (kindName == null) return null;
            return new CommandMenuEntry.TypeArg(
                "Rename", "new_name", "dungeontrain editor part rename", "", kindName[1]);
        }
        return switch (ctx.category()) {
            case "carriages" -> isReservedCarriageBuiltin(model) ? null : new CommandMenuEntry.TypeArg(
                "Rename", "new_name",
                "dungeontrain editor save",
                "", model);
            case "contents" -> isReservedContentsBuiltin(model) ? null : new CommandMenuEntry.TypeArg(
                "Rename", "new_name",
                "dungeontrain editor contents save",
                "", model);
            default -> null;
        };
    }

    /** Match server-side carriage built-in names so the Rename row hides for them. Mirrors PROTECTED_BUILTINS in EditorCommand. */
    private static boolean isReservedCarriageBuiltin(String id) {
        return "standard".equals(id) || "flatbed".equals(id);
    }

    /** Match server-side contents built-in names. Server rejects rename for any builtin via {@code current.isBuiltin()}. */
    private static boolean isReservedContentsBuiltin(String id) {
        return "default".equals(id);
    }

    /**
     * Everything this player has uploaded to their relay profile, and the one button that puts a
     * build on the train.
     *
     * <p>Sits in File beside New and Save, because a build you have just made and a build you have
     * already published are the same question asked twice — the same reasoning as the Train
     * Builder's pause menu, which is where this screen came from. The screen itself was always
     * world-agnostic; only the builder's button was, so the editor gets its own rather than a copy
     * of it.</p>
     *
     * <p>Package-private rather than private because {@link MainMenuScreen} offers the same row at
     * the root of the menu, for a player who is not standing in a plot. One definition, so the two
     * rows cannot drift into saying different things or opening different screens.</p>
     *
     * <p>The menu is closed first so the profile screen replaces it cleanly rather than stacking on
     * top of it. A null parent means Back returns to the game.</p>
     */
    static CommandMenuEntry myBuildsEntry() {
        return new CommandMenuEntry.ClientAction(
            Component.translatable("gui.dungeontrain.builder.profile").getString(),
            () -> {
                CommandMenuState.close();
                Minecraft.getInstance().setScreen(new BuilderProfileScreen(null));
            });
    }
}
