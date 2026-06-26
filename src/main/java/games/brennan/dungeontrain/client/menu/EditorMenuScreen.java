package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.VersionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu shown when the player is inside an editor plot. DevMode is a live
 * toggle driven by {@link EditorStatusHudOverlay} and is only surfaced on
 * non-{@code main} builds (see {@link #shouldShowDevModeToggle(String)} —
 * release jars built from {@code main} hide the row entirely). Enter drills
 * into category selection. Save has an "All" companion for category-wide
 * saves. New / Remove act on the model the player is currently standing in —
 * New duplicates it under a typed name, Remove deletes the model after a
 * confirmation prompt. Exit runs {@code /dungeontrain editor exit} —
 * unwinds the active editor session, clears the editor plots, and
 * teleports the player back to where they entered the editor from.
 */
public final class EditorMenuScreen implements MenuScreen {

    @Override public String title() { return "Editor"; }

    @Override public List<CommandMenuEntry> entries() {
        boolean devmode = EditorStatusHudOverlay.isDevModeOn();
        String category = EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT);
        // `model` is the friendly path string (HUD-style, may contain "/").
        // `modelId` is the bare command-token id used to dispatch /dt editor ...
        // `modelName` is the trailing variant-name segment of the model — for
        // track-side models the path string is e.g. "track / track2" and the
        // bare name is "track2". For carriages and contents modelId/modelName
        // are identical to model. Only modelId/modelName are safe to splice
        // into a command string.
        String model = EditorStatusHudOverlay.model();
        String modelId = EditorStatusHudOverlay.modelId();
        String modelName = EditorStatusHudOverlay.modelName();
        int currentWeight = EditorStatusHudOverlay.weight();

        List<CommandMenuEntry> out = new ArrayList<>();
        if (shouldShowDevModeToggle(VersionInfo.BRANCH)) {
            out.add(new CommandMenuEntry.Toggle(
                "DevMode", devmode,
                "dungeontrain editor devmode on",
                "dungeontrain editor devmode off"
            ));
        }
        out.add(new CommandMenuEntry.DrillIn("Enter", new EnterCategoryMenuScreen()));

        // Parts have their own Save / Reset commands — `dungeontrain save`
        // dispatches via EditorCategory.locate which doesn't see part plots,
        // so route through the part-aware /editor part subcommands instead.
        if ("parts".equals(category)) {
            out.add(new CommandMenuEntry.Split(
                new CommandMenuEntry.Run("Save", "dungeontrain editor part save"),
                new CommandMenuEntry.Run("All", "dungeontrain editor part save all"),
                0.80
            ));
            CommandMenuEntry partsClear = clearEntryFor(category, model);
            if (partsClear != null) out.add(partsClear);
            int sep = model.indexOf(':');
            if (sep > 0 && sep < model.length() - 1) {
                String kind = model.substring(0, sep);
                String name = model.substring(sep + 1);
                out.add(new CommandMenuEntry.Split(
                    new CommandMenuEntry.DrillIn(
                        "New",
                        new NewSourcePickerScreen(
                            NewSourcePickerScreen.Category.PARTS, kind, name)),
                    new CommandMenuEntry.DrillIn(
                        "Remove",
                        new ConfirmScreen("Remove '" + model + "'?",
                            "dungeontrain editor part reset " + kind + " " + name)),
                    0.50
                ));
                out.add(new CommandMenuEntry.TypeArg(
                    "Rename", "new_name",
                    "dungeontrain editor part rename",
                    "", name));
            }
            addMirrorToggles(out);
            out.add(new CommandMenuEntry.DrillIn("Package", new PackageListScreen()));
            out.add(new CommandMenuEntry.Run("Exit", "dungeontrain editor exit"));
            return out;
        }

        // Save / Save All — works for every category.
        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.Run("Save", "dungeontrain save"),
            new CommandMenuEntry.Run("All", "dungeontrain save all"),
            0.80
        ));

        // Reset | Clear — paired destructive actions. Reset deletes the
        // on-disk template; Clear wipes interior blocks to air. Clear is
        // only available for user-authorable categories, so for the others
        // (tracks / pillars / tunnels / architecture) fall back to a solo
        // Reset row.
        CommandMenuEntry resetEntry = new CommandMenuEntry.Run("Reset", "dungeontrain reset");
        CommandMenuEntry clearEntry = clearEntryFor(category, model);
        if (clearEntry != null) {
            out.add(new CommandMenuEntry.Split(resetEntry, clearEntry, 0.50));
        } else {
            out.add(resetEntry);
        }

        // New / Remove — only meaningful for categories whose models are
        // user-authorable (carriages, contents) or whose registry supports
        // deletion (tracks). For architecture the concept doesn't apply, so
        // the row is omitted rather than showing buttons that error on click.
        CommandMenuEntry newEntry = newEntryFor(category, modelId, model);
        CommandMenuEntry removeEntry = removeEntryFor(category, modelId, model);
        if (newEntry != null && removeEntry != null) {
            out.add(new CommandMenuEntry.Split(newEntry, removeEntry, 0.50));
        }

        // Rename — only for user-authorable categories with a non-builtin id.
        // Pre-fills the typing field with the current model name.
        CommandMenuEntry renameEntry = renameEntryFor(category, model);
        if (renameEntry != null) out.add(renameEntry);

        // Editor Menus — carriage variants only. Master toggle for the
        // world-space editor menus: drives the auto-opening part-position
        // menu's persistent flag and, when turned off, also closes any open
        // tap-to-open block-variant / container-contents menus.
        if ("carriages".equals(category)) {
            boolean pmEnabled = EditorStatusHudOverlay.isEditorMenusVisible();
            out.add(new CommandMenuEntry.Toggle(
                "Editor Menus", pmEnabled,
                "dungeontrain editor editormenus on",
                "dungeontrain editor editormenus off"
            ));
            // Contents — drilldown listing every registered content with a
            // per-row red/green toggle so the author can exclude specific
            // contents from this carriage's spawn pool. Only shown when a
            // concrete variant id is in scope (modelId non-empty).
            if (modelId != null && !modelId.isEmpty()) {
                out.add(new CommandMenuEntry.DrillIn(
                    "Contents",
                    new CarriageContentsAllowScreen(modelId)));
            }
        }

        // Weight — Triple row: [-] / Weight (N) / [+] for every category that
        // has a weight pool (carriages, tracks, contents). Side cells nudge by
        // 1 server-side and stay open so the player can tap-tap-tap; middle
        // cell drops into typing mode for an exact value. Label refreshes via
        // tick rebuild as the HUD picks up the new value.
        CommandMenuEntry weightRow = weightTripleFor(category, modelId, modelName, currentWeight);
        if (weightRow != null) out.add(weightRow);

        // Spawn gate — min/max Diff-Level steppers (same categories as Weight) plus a Phases
        // drilldown to the OW/Nether/Void/End checkbox popup. Only shown for weighted, addressable
        // models (weightRow != null is the exact same gate).
        if (weightRow != null) {
            int minLv = EditorStatusHudOverlay.minLevel();
            int maxLv = EditorStatusHudOverlay.maxLevel();
            CommandMenuEntry minRow = levelTripleFor(category, modelId, modelName, "minlevel",
                "Min Lv (" + minLv + ")", "0-100");
            if (minRow != null) out.add(minRow);
            CommandMenuEntry maxRow = levelTripleFor(category, modelId, modelName, "maxlevel",
                "Max Lv (" + (maxLv < 0 ? "all" : Integer.toString(maxLv)) + ")", "-1..100");
            if (maxRow != null) out.add(maxRow);
            out.add(new CommandMenuEntry.DrillIn(
                "Phases", new PhaseSelectScreen(category, modelId, modelName)));
        }

        // Editor mirror toggles — author one octant, the editor mirrors live
        // (and rebuilds on save) across the enabled axes. Available in every
        // template plot (off by default outside tunnels).
        if (("carriages".equals(category) || "contents".equals(category) || "tracks".equals(category))
            && modelName != null && !modelName.isEmpty()) {
            addMirrorToggles(out);
        }

        out.add(new CommandMenuEntry.DrillIn("Package", new PackageListScreen()));
        out.add(new CommandMenuEntry.Run("Exit", "dungeontrain editor exit"));
        return out;
    }

    /**
     * Append a {@code Mirror} header label followed by a {@code [X | Y | Z | V]}
     * toggle row, wired to the position-resolved {@code editor mirror <axis> on|off}
     * command (resolves whichever plot the player stands in). X/Y/Z mirror
     * structural blocks across an axis; the {@code V} toggle additionally mirrors
     * the per-cell variant pools (opt-in — off by default). Toggle state (and the
     * green on-tint) is the server-pushed {@link EditorStatusHudOverlay} mirror flags.
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
    }

    /**
     * Returns {@code true} when the DevMode toggle row should be added to the
     * editor menu. Hidden on release builds (jar built from {@code main}); any
     * other branch — feature branches, detached-HEAD short SHAs, the {@code "?"}
     * fallback when git detection failed at build time, or {@code null} — shows
     * the toggle so devs aren't locked out when build metadata is missing.
     * Extracted as a pure predicate so the unit test can pin behavior without
     * having to mutate {@link VersionInfo}'s static initializer.
     */
    static boolean shouldShowDevModeToggle(String branch) {
        return !"main".equals(branch);
    }

    /**
     * Build a {@link CommandMenuEntry.Triple} for the active model's weight,
     * or null when the category has no weight pool / no addressable model.
     * Extracted so the unit test can pin command strings without standing up
     * the full menu.
     *
     * <p>Command shapes:
     * <ul>
     *   <li>{@code carriages}: {@code dungeontrain editor weight <modelId> {dec|inc|""}}</li>
     *   <li>{@code tracks}: {@code dungeontrain editor tracks weight <modelId> <modelName> {dec|inc|""}}</li>
     *   <li>{@code contents}: {@code dungeontrain editor contents weight <modelId> {dec|inc|""}}</li>
     * </ul>
     *
     * <p>{@code modelId} (not {@code model}) is spliced into commands so
     * track-side models with friendly path strings ({@code "track / track2"})
     * don't break the parser. For carriages and contents the two are equal;
     * for tracks {@code modelName} carries the trailing variant name segment.</p>
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
     * Build a {@link CommandMenuEntry.Triple} stepper for a per-template spawn-gate level bound,
     * or null when the category has no gate / no addressable model. {@code sub} is the gate
     * subcommand ({@code minlevel} / {@code maxlevel}); {@code label} is the pre-rendered cell
     * label (caller formats the current value); {@code hint} is the typing-mode placeholder.
     * Command shapes mirror {@link #weightTripleFor}:
     * <ul>
     *   <li>{@code carriages}: {@code dungeontrain editor <sub> <modelId> {dec|inc|""}}</li>
     *   <li>{@code tracks}: {@code dungeontrain editor tracks <sub> <modelId> <modelName> {dec|inc|""}}</li>
     *   <li>{@code contents}: {@code dungeontrain editor contents <sub> <modelId> {dec|inc|""}}</li>
     * </ul>
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
            case "contents" -> prefix = "dungeontrain editor contents " + sub + " " + modelId;
            default -> { return null; }
        }
        CommandMenuEntry minus  = new CommandMenuEntry.Stay("-", prefix + " dec");
        CommandMenuEntry middle = new CommandMenuEntry.TypeArg(label, hint, prefix);
        CommandMenuEntry plus   = new CommandMenuEntry.Stay("+", prefix + " inc");
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }

    /**
     * "New" drills into a {@link NewSourcePickerScreen} for carriages and
     * contents (Blank / Current / Standard seed picker before naming).
     * For {@code tracks} the {@code modelId} is the kind tag the player is
     * standing on ({@code track}, {@code pillar_top},
     * {@code tunnel_section}, {@code adjunct_stairs}, ...) — passed to
     * {@code /dt editor tracks new <kind> <typed-name>}, which clones the
     * variant the player is currently standing on under the new name and
     * teleports them to the new plot. Returns null for categories that
     * don't support author-authored new models.
     *
     * <p>{@code modelId} is the command-token id ({@code track}); {@code model}
     * is the friendly path string ({@code track / track2}). Carriages and
     * contents pass {@code modelId} to the picker so any preview state keys
     * off the same id the server uses.</p>
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
            default -> null;
        };
    }

    /**
     * "Remove" deletes the current model's config-dir file via
     * {@code /dt editor reset <id>} / {@code /dt editor contents reset <id>}.
     * Drills into a ConfirmScreen first so mis-clicks don't silently wipe
     * the user's work.
     *
     * <p>For {@code tracks} the menu drills into a confirm that fires
     * {@code /dt editor tracks reset <kind>} — that command no-ops with a
     * friendly error when the active variant is the synthetic
     * {@code default} (you can't remove the built-in fallback).</p>
     *
     * <p>{@code modelId} is what gets spliced into the command (must be a
     * single command token); {@code model} is the friendly path used in the
     * confirm prompt label.</p>
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
            default -> null;
        };
    }

    /**
     * "Clear" wipes every interior block of the current plot to air via
     * {@code /dt editor clear}. Drills into a ConfirmScreen first since the
     * action is destructive — same gating as Remove. Returns null for
     * categories without a single addressable model id (tracks, pillars,
     * tunnels, architecture).
     */
    private static CommandMenuEntry clearEntryFor(String category, String model) {
        if (model == null || model.isEmpty()) return null;
        return switch (category) {
            case "carriages", "contents", "parts" -> new CommandMenuEntry.DrillIn(
                "Clear",
                new ConfirmScreen("Clear all blocks in '" + model + "'?",
                    "dungeontrain editor clear"));
            default -> null;
        };
    }

    /**
     * "Rename" pre-fills the typing field with the current model id and on
     * submit runs the category's {@code save <new_name>} subcommand — both
     * carriages and contents implement that as a true rename (saveAs:
     * delete-old + write-new + registry update). Returns null for builtin
     * variants and for categories that don't support author-authored renames.
     */
    private static CommandMenuEntry renameEntryFor(String category, String model) {
        if (model == null || model.isEmpty()) return null;
        return switch (category) {
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
}
