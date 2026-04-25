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
        String model = EditorStatusHudOverlay.model();

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
        // user-authorable (carriages, contents). For tracks / pillars /
        // tunnels / architecture the concept doesn't apply, so the row is
        // omitted rather than showing buttons that error on click.
        CommandMenuEntry newEntry = newEntryFor(category, model);
        CommandMenuEntry removeEntry = removeEntryFor(category, model);
        if (newEntry != null && removeEntry != null) {
            out.add(new CommandMenuEntry.Split(newEntry, removeEntry, 0.50));
        }

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
     * "New" drills into a {@link NewSourcePickerScreen} so the user picks a
     * seed (Blank / Current / Standard) before naming the model. The typed
     * name then becomes the new id; the picker's source token decides what
     * the new plot is stamped with. Returns null for categories that don't
     * support author-authored new models (tracks, pillars, tunnels, etc.).
     */
    private static CommandMenuEntry newEntryFor(String category, String model) {
        return switch (category) {
            case "carriages" -> new CommandMenuEntry.DrillIn(
                "New",
                new NewSourcePickerScreen(
                    NewSourcePickerScreen.Category.CARRIAGES, null, model));
            case "contents" -> new CommandMenuEntry.DrillIn(
                "New",
                new NewSourcePickerScreen(
                    NewSourcePickerScreen.Category.CONTENTS, null, model));
            default -> null;
        };
    }

    /**
     * "Remove" deletes the current model's config-dir file via
     * {@code /dt editor reset <id>} / {@code /dt editor contents reset <id>}.
     * Drills into a ConfirmScreen first so mis-clicks don't silently wipe
     * the user's work.
     */
    private static CommandMenuEntry removeEntryFor(String category, String model) {
        if (model == null || model.isEmpty()) return null;
        return switch (category) {
            case "carriages" -> new CommandMenuEntry.DrillIn(
                "Remove",
                new ConfirmScreen("Remove '" + model + "'?",
                    "dungeontrain editor reset " + model));
            case "contents" -> new CommandMenuEntry.DrillIn(
                "Remove",
                new ConfirmScreen("Remove '" + model + "'?",
                    "dungeontrain editor contents reset " + model));
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
}
