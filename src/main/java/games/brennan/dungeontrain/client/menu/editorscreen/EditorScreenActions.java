package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CarriageContentsAllowScreen;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.EditorMenuScreen;
import games.brennan.dungeontrain.client.menu.MenuHeaderAction;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import games.brennan.dungeontrain.client.menu.NewSourcePickerScreen;
import games.brennan.dungeontrain.client.menu.PackageListScreen;
import games.brennan.dungeontrain.client.menu.PortalTestSaveCheckScreen;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.client.menu.UnsavedCheckScreen;
import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotActionPacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Every control of the inventory-style editor screen, resolved to the {@link CommandMenuEntry}
 * it dispatches — pure functions of the selection and of where the player stands, so the whole
 * table is unit-testable and every command shape is pinned.
 *
 * <p>The one rule that matters: <b>position-resolved commands act on the plot the player stands
 * in.</b> Save, Reset, Clear, Undo, Redo, Rename, the room geometry rows and Test the Carriage
 * all read the server's idea of "the current plot", so they are only offered while the selection
 * is that plot. Everything addressed by id — weight, gate, phases, stage, remove, enter — works on
 * any selection. Save / Reset / Clear on another plot go through {@link EditorPlotActionPacket},
 * which is addressed, where the category supports it.</p>
 */
public final class EditorScreenActions {

    /** What the builders need to know about the selection and the player. */
    public record Ctx(
        VariantKey selection,
        EditorTypeMenusPacket.Variant variant,
        int selfWeight,
        VariantKey standing,
        PlotCategory stampedCategory,
        boolean dirty
    ) {
        public boolean hasSelection() {
            return selection != null && variant != null;
        }

        /** True when the selected template is the plot the player stands in. */
        public boolean standingInSelection() {
            return hasSelection() && standing != null && standing.sameTemplate(selection);
        }

        public PlotCategory category() {
            return hasSelection() ? selection.category() : null;
        }

        public boolean isSubVariant() {
            return hasSelection() && selection.isSubVariant();
        }
    }

    /** One icon of the file row. {@code entry} null means disabled, with {@code disabledKey} saying why. */
    public record Icon(String id, String labelKey, CommandMenuEntry entry, String disabledKey) {
        public boolean enabled() {
            return entry != null;
        }
    }

    private EditorScreenActions() {}

    // ------------------------------------------------------------------
    // Icon row
    // ------------------------------------------------------------------

    /** Save · Rename · Remove | Undo · Redo | Reset · Clear | Package, in that order. */
    public static List<Icon> icons(Ctx ctx, Consumer<EditorPlotActionPacket> sendPacket) {
        List<Icon> out = new ArrayList<>(8);
        boolean here = ctx.standingInSelection();
        PlotCategory cat = ctx.category();
        String model = ctx.hasSelection() ? ctx.selection().displayName() : "";
        boolean parts = cat == PlotCategory.PARTS;

        out.add(new Icon("save", EditorScreenLang.ICON_SAVE,
            here ? new CommandMenuEntry.Stay("Save", parts ? EditorMenuScreen.PART_SAVE_COMMAND : EditorMenuScreen.SAVE_COMMAND)
                 : packetAction(ctx, EditorPlotActionPacket.Action.SAVE, sendPacket),
            EditorScreenLang.DISABLED_STAND_HERE));

        out.add(new Icon("rename", EditorScreenLang.ICON_RENAME, renameEntry(ctx),
            EditorScreenLang.DISABLED_BUILTIN));

        out.add(new Icon("remove", EditorScreenLang.ICON_REMOVE, removeEntry(ctx),
            EditorScreenLang.DISABLED_NOT_HERE));

        out.add(new Icon("undo", EditorScreenLang.ICON_UNDO,
            here ? new CommandMenuEntry.Stay("Undo", "dungeontrain editor undo") : null,
            EditorScreenLang.DISABLED_STAND_HERE));
        out.add(new Icon("redo", EditorScreenLang.ICON_REDO,
            here ? new CommandMenuEntry.Stay("Redo", "dungeontrain editor redo") : null,
            EditorScreenLang.DISABLED_STAND_HERE));

        out.add(new Icon("reset", EditorScreenLang.ICON_RESET,
            here && !parts ? new CommandMenuEntry.Stay("Reset", "dungeontrain reset")
                 : packetAction(ctx, EditorPlotActionPacket.Action.RESET, sendPacket),
            EditorScreenLang.DISABLED_STAND_HERE));

        CommandMenuEntry clear = here ? EditorMenuScreen.clearEntryFor(cat, model) : null;
        out.add(new Icon("clear", EditorScreenLang.ICON_CLEAR,
            clear != null ? clear : packetAction(ctx, EditorPlotActionPacket.Action.CLEAR, sendPacket),
            EditorScreenLang.DISABLED_STAND_HERE));

        out.add(new Icon("package", EditorScreenLang.ICON_PACKAGE,
            new CommandMenuEntry.DrillIn("Package", new PackageListScreen()), null));
        return out;
    }

    /**
     * Rename the selected template, from wherever the player is standing.
     *
     * <p>Addressed by id rather than by position, so it renames what the pane is showing rather
     * than whatever plot the author happens to be in. The server still refuses when the template's
     * category is not the stamped one — its plots are cleared then, and the rename captures blocks
     * from the plot.</p>
     *
     * <p>Null for built-ins, sub-variants, and the categories with no rename verb.</p>
     */
    static CommandMenuEntry renameEntry(Ctx ctx) {
        if (!ctx.hasSelection() || ctx.isSubVariant()) return null;
        VariantKey sel = ctx.selection();
        String id = sel.modelId();
        String label = EditorScreenLang.text(EditorScreenLang.ICON_RENAME);
        return switch (sel.category()) {
            case CARRIAGES -> EditorMenuScreen.isReservedCarriageBuiltin(id) ? null
                : new CommandMenuEntry.TypeArg(label, "new_name",
                    "dungeontrain editor rename " + id, "", id);
            case CONTENTS -> EditorMenuScreen.isReservedContentsBuiltin(id) ? null
                : new CommandMenuEntry.TypeArg(label, "new_name",
                    "dungeontrain editor contents rename " + id, "", id);
            // Parts rename through their own kind:name verb; tracks and rooms have none.
            case PARTS, TRACKS, PORTALS, ARCHITECTURE -> null;
        };
    }

    /**
     * Remove is addressed by id, so it works on any selection. A sub-variant is removed through
     * its own template id (contents) or its room name (portals), the same commands the old menu
     * sent from inside the member's plot.
     */
    static CommandMenuEntry removeEntry(Ctx ctx) {
        if (!ctx.hasSelection()) return null;
        VariantKey sel = ctx.selection();
        if (sel.category() == PlotCategory.PARTS) {
            return new CommandMenuEntry.DrillIn("Remove",
                new games.brennan.dungeontrain.client.menu.ConfirmScreen(
                    "Remove '" + sel.modelName() + "'?",
                    "dungeontrain editor part reset " + sel.modelId() + " " + sel.modelName()));
        }
        return EditorMenuScreen.removeEntryFor(sel.category(), sel.modelId(), sel.displayName());
    }

    /**
     * Save / Reset / Clear on a plot the player is not standing in: the addressed packet the
     * world-space panels use, for categories whose plots have an action row. Null otherwise.
     */
    static CommandMenuEntry packetAction(Ctx ctx, EditorPlotActionPacket.Action action,
                                         Consumer<EditorPlotActionPacket> sendPacket) {
        if (!ctx.hasSelection() || ctx.isSubVariant()) return null;
        PlotCategory cat = ctx.category();
        if (cat == null || !cat.hasActionRow()) return null;
        VariantKey sel = ctx.selection();
        EditorPlotActionPacket packet = new EditorPlotActionPacket(
            cat.id(), sel.modelId(), sel.modelName(), action);
        return new CommandMenuEntry.ClientAction(action.name(), () -> sendPacket.accept(packet));
    }

    // ------------------------------------------------------------------
    // Header, enter, test
    // ------------------------------------------------------------------

    /** The header Save-all icon: parts route through the part-aware subcommand. */
    public static MenuHeaderAction saveAll(Ctx ctx, long nowMillis) {
        PlotCategory cat = ctx.standing() != null ? ctx.standing().category() : ctx.category();
        MenuHeaderAction base = EditorMenuScreen.saveHeaderAction(cat, ctx.dirty(), nowMillis);
        String command = cat == PlotCategory.PARTS ? "dungeontrain editor part save all" : "dungeontrain save all";
        String label = EditorScreenLang.text(ctx.dirty() ? EditorScreenLang.SAVE_ALL_DIRTY : EditorScreenLang.SAVE_ALL);
        return new MenuHeaderAction(base.icon(), label, command, base.tint());
    }

    /**
     * Go and stand in the selection. Same stamped category: the plain enter command. Another
     * category: through the unsaved check, which switches category and follows up with the
     * enter — the path every cross-category jump in the mod takes. Null when nothing is selected.
     */
    public static CommandMenuEntry enterEntry(Ctx ctx) {
        if (!ctx.hasSelection()) return null;
        VariantKey sel = ctx.selection();
        String command = EditorPlotTeleport.commandFor(sel.category(), sel.modelId(), sel.modelName());
        if (command == null) return null;
        String label = EditorScreenLang.text(EditorScreenLang.ENTER);
        if (ctx.stampedCategory() != null && sel.category().owner() == ctx.stampedCategory().owner()) {
            return new CommandMenuEntry.Run(label, command);
        }
        return new CommandMenuEntry.DrillIn(label,
            new UnsavedCheckScreen(sel.category().owner().id(), command));
    }

    /** Test the Carriage: dimensions only, and only from inside the room it tests. */
    public static CommandMenuEntry testEntry(Ctx ctx) {
        if (!ctx.standingInSelection() || ctx.category() != PlotCategory.PORTALS) return null;
        return new CommandMenuEntry.DrillIn(EditorScreenLang.text(EditorScreenLang.TEST_CARRIAGE),
            new PortalTestSaveCheckScreen(ctx.selection().modelName()));
    }

    // ------------------------------------------------------------------
    // Per-plot settings rows
    // ------------------------------------------------------------------

    /**
     * The world-space plot panel's rows for the selection, in its order: weight, then the room
     * geometry (dimensions, standing only), then the gate, then the contents allow-list.
     *
     * @param portalRows the HUD-backed room rows, supplied so they are only read when they apply
     */
    public static List<CommandMenuEntry> settingRows(Ctx ctx, Supplier<List<CommandMenuEntry>> portalRows,
                                                     Supplier<String> roomMode) {
        List<CommandMenuEntry> out = new ArrayList<>();
        if (!ctx.hasSelection()) return out;
        // Weight, the level bounds, the phases and a room's length, width and height are edited on
        // the data sheet, on the lines that show them. Only what the sheet has no room for lands
        // here: the Stage link, the contents allow-list, and what a room does at its walls.
        if (ctx.standingInSelection() && ctx.category() == PlotCategory.PORTALS) {
            for (CommandMenuEntry row : portalRows.get()) {
                if (!isRoomSizeRow(row)) out.add(row);
            }
        }
        if (!ctx.isSubVariant()) {
            addIfPresent(out, stageRow(ctx));
        }
        addIfPresent(out, contentsAllowEntry(ctx, roomMode));
        return out;
    }

    /** True for the length, width and height steppers, which the Size line now carries. */
    static boolean isRoomSizeRow(CommandMenuEntry row) {
        TemplateDataSheet.Stepper stepper = TemplateDataSheet.Stepper.of(row);
        return stepper != null && stepper.isRoomAxis();
    }

    /** The Stage chip, or the Custom picker when the template is unlinked. */
    static CommandMenuEntry stageRow(Ctx ctx) {
        VariantKey sel = ctx.selection();
        EditorTypeMenusPacket.Variant v = ctx.variant();
        if (!sel.category().hasGate() || v.phaseMask() == EditorTypeMenusPacket.Variant.NO_GATE) return null;
        // A linked Stage already shows as the Spawns line; the row would say it twice.
        if (v.isStageLinked()) return null;
        return new CommandMenuEntry.DrillIn(EditorScreenLang.text(EditorScreenLang.STAGE_CUSTOM),
            new StagePickerScreen(sel.category(), sel.modelId(), sel.modelName(), ""));
    }

    /** The weight stepper for a key, for the sheet to take apart. Null when there is no weight pool. */
    public static CommandMenuEntry weightRow(VariantKey sel, int weight) {
        if (sel == null || weight == EditorPlotLabelsPacket.NO_WEIGHT) return null;
        if (!sel.isSubVariant()) {
            return EditorMenuScreen.weightTripleFor(sel.category(), sel.modelId(), sel.modelName(), weight);
        }
        String dec;
        String inc;
        String prefix;
        switch (sel.category()) {
            case CONTENTS -> {
                dec = EditorPlotTeleport.groupMemberWeightCommandFor(sel.parentId(), sel.modelName(), "dec");
                inc = EditorPlotTeleport.groupMemberWeightCommandFor(sel.parentId(), sel.modelName(), "inc");
                prefix = "dungeontrain editor contents group set-weight " + sel.parentId() + " " + sel.modelName();
            }
            case PORTALS -> {
                dec = EditorPlotTeleport.portalRoomGroupWeightCommandFor(sel.parentId(), sel.modelName(), "dec");
                inc = EditorPlotTeleport.portalRoomGroupWeightCommandFor(sel.parentId(), sel.modelName(), "inc");
                prefix = "dungeontrain editor portals group set-weight " + sel.parentId() + " " + sel.modelName();
            }
            // Track-side groups have no per-member weight verb yet.
            default -> {
                return null;
            }
        }
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.Stay("-", dec),
            new CommandMenuEntry.TypeArg(EditorScreenLang.text(EditorScreenLang.WEIGHT, weight), "0-100", prefix),
            new CommandMenuEntry.Stay("+", inc),
            0.10, 0.90);
    }

    /** A level-bound stepper for a key, for the sheet to take apart. */
    public static CommandMenuEntry levelRow(VariantKey sel, String sub, String shown) {
        if (sel == null || sel.isSubVariant()) return null;
        return EditorMenuScreen.levelTripleFor(sel.category(), sel.modelId(), sel.modelName(),
            sub, "(" + shown + ")", sub.equals("minlevel") ? "0-1000" : "-1..1000");
    }

    /**
     * The contents allow-list: every carriage has one; a room has one while its Contents setting
     * furnishes it, which is only knowable for the room the player stands in.
     */
    static CommandMenuEntry contentsAllowEntry(Ctx ctx, Supplier<String> roomMode) {
        VariantKey sel = ctx.selection();
        String label = EditorScreenLang.text(EditorScreenLang.CONTENTS_ALLOW);
        if (sel.category() == PlotCategory.CARRIAGES && !sel.isSubVariant()) {
            return new CommandMenuEntry.DrillIn(label, CarriageContentsAllowScreen.forCarriage(sel.modelId()));
        }
        if (sel.category() == PlotCategory.PORTALS && ctx.standingInSelection()
            && PortalRoomSettings.parse(roomMode.get()).contents().furnishes()) {
            return new CommandMenuEntry.DrillIn(label, CarriageContentsAllowScreen.forPortalRoom(sel.modelName()));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // New
    // ------------------------------------------------------------------

    /**
     * The "+" tile of a type strip. Carriages and contents pick a source first; parts pick within
     * their kind; tracks and rooms clone the strip's first variant under a typed name.
     */
    public static CommandMenuEntry newEntry(PlotCategory stripCategory, String stripModelId,
                                            String firstName, VariantKey standing) {
        if (stripCategory == null) return null;
        String current = standing != null && standing.category() == stripCategory
            ? standing.displayName() : firstName;
        if (stripCategory == PlotCategory.PARTS) {
            return new CommandMenuEntry.DrillIn("New",
                new NewSourcePickerScreen(NewSourcePickerScreen.Category.PARTS, stripModelId, current));
        }
        String modelId = switch (stripCategory) {
            case TRACKS, PORTALS -> stripModelId;
            default -> current;
        };
        return EditorMenuScreen.newEntryFor(stripCategory, modelId, current);
    }

    /** The "+" tile of a sub-variant grid: a new member of {@code parent}'s group. */
    public static CommandMenuEntry newSubVariantEntry(VariantKey parent, VariantKey standing) {
        if (parent == null) return null;
        String source = standing != null && standing.category() == parent.category()
            ? standing.displayName() : parent.displayName();
        return switch (parent.category()) {
            case CONTENTS -> new CommandMenuEntry.DrillIn("New sub-variant",
                new NewSourcePickerScreen(NewSourcePickerScreen.Category.CONTENTS_SUB_VARIANT,
                    null, parent.displayName(), source));
            case PORTALS -> new CommandMenuEntry.DrillIn("New sub-variant",
                new NewSourcePickerScreen(NewSourcePickerScreen.Category.PORTAL_ROOM_SUB_VARIANT,
                    null, parent.displayName(), source));
            default -> null;
        };
    }

    private static void addIfPresent(List<CommandMenuEntry> out, CommandMenuEntry entry) {
        if (entry != null) out.add(entry);
    }
}
