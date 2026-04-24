package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Two-button confirmation prompt — used for destructive actions like
 * "Remove this model". The title is shown as the breadcrumb, and the entry
 * rows are a Yes/No split + Back.
 *
 * <p>Yes runs the supplied command and closes the menu (via the usual
 * {@link CommandMenuEntry.Run} path). No pops back to the previous screen.
 */
public final class ConfirmScreen implements MenuScreen {

    private final String title;
    private final String onConfirmCommand;

    public ConfirmScreen(String title, String onConfirmCommand) {
        this.title = title;
        this.onConfirmCommand = onConfirmCommand;
    }

    @Override public String title() { return title; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Yes, confirm", onConfirmCommand),
            new CommandMenuEntry.Back("Cancel")
        );
    }
}
