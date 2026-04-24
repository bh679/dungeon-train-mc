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

        // Save / Save All — works for every category.
        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.Run("Save", "dungeontrain save"),
            new CommandMenuEntry.Run("All", "dungeontrain save all"),
            0.80
        ));

        // Reset — no "all" form server-side yet.
        out.add(new CommandMenuEntry.Run("Reset", "dungeontrain reset"));

        // New / Remove — only meaningful for categories whose models are
        // user-authorable (carriages, contents). For tracks / pillars /
        // tunnels / architecture the concept doesn't apply, so the row is
        // omitted rather than showing buttons that error on click.
        CommandMenuEntry newEntry = newEntryFor(category, model);
        CommandMenuEntry removeEntry = removeEntryFor(category, model);
        if (newEntry != null && removeEntry != null) {
            out.add(new CommandMenuEntry.Split(newEntry, removeEntry, 0.50));
        }

        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /**
     * "New" duplicates the current model. The typed name becomes the new id;
     * the current model id is passed as the {@code [source]} arg so the
     * duplicate inherits its contents. Returns null for categories that
     * don't support author-authored new models.
     */
    private static CommandMenuEntry newEntryFor(String category, String model) {
        return switch (category) {
            case "carriages" -> new CommandMenuEntry.TypeArg(
                "New", "name",
                "dungeontrain editor new",
                fallback(model, "standard"));
            case "contents" -> new CommandMenuEntry.TypeArg(
                "New", "name",
                "dungeontrain editor contents new",
                fallback(model, "default"));
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

    private static String fallback(String value, String alt) {
        return (value == null || value.isEmpty()) ? alt : value;
    }
}
