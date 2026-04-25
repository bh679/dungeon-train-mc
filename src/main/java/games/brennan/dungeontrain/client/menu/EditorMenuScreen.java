package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu shown when the player is inside an editor plot. DevMode is a live
 * toggle driven by {@link EditorStatusHudOverlay}. Enter drills into
 * category selection. Save has an "All" companion for category-wide saves.
 * New / Remove act on the model the player is currently standing in —
 * New duplicates it under a typed name, Remove deletes the model after a
 * confirmation prompt. Back pops to the main menu.
 */
public final class EditorMenuScreen implements MenuScreen {

    @Override public String title() { return "Editor"; }

    @Override public List<CommandMenuEntry> entries() {
        boolean devmode = EditorStatusHudOverlay.isDevModeOn();
        String category = EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT);
        // `model` is the friendly path string (HUD-style, may contain "/").
        // `modelId` is the bare command-token id used to dispatch /dt editor ...
        // For carriages and contents the two are identical; for track-side
        // models (track, pillar_*, tunnel_*) they diverge — only modelId is
        // safe to splice into a command string.
        String model = EditorStatusHudOverlay.model();
        String modelId = EditorStatusHudOverlay.modelId();

        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(new CommandMenuEntry.Toggle(
            "DevMode", devmode,
            "dungeontrain editor devmode on",
            "dungeontrain editor devmode off"
        ));
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
            out.add(new CommandMenuEntry.Back("< Back"));
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

        // Weight — carriage variants only. Triple row: [-] / Weight (N) / [+].
        // Side cells nudge by 1 server-side and stay open so the player can
        // tap-tap-tap; middle cell drops into typing mode for an exact value.
        // Label refreshes via tick rebuild as the HUD picks up the new value.
        if ("carriages".equals(category) && model != null && !model.isEmpty()) {
            int w = EditorStatusHudOverlay.weight();
            String label = w >= 0 ? "Weight (" + w + ")" : "Weight";
            CommandMenuEntry minus  = new CommandMenuEntry.Stay(
                "-", "dungeontrain editor weight " + model + " dec");
            CommandMenuEntry weight = new CommandMenuEntry.TypeArg(
                label, "0-100", "dungeontrain editor weight " + model);
            CommandMenuEntry plus   = new CommandMenuEntry.Stay(
                "+", "dungeontrain editor weight " + model + " inc");
            out.add(new CommandMenuEntry.Triple(minus, weight, plus, 0.10, 0.90));
        }

        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
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
