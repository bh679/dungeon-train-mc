package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Typed-input prompt for duplicating a stage — opened from the Stage Blocks panel's Duplicate
 * button. The {@link CommandMenuEntry.TypeArg} row collects the new stage id and dispatches
 * {@code dungeontrain editor stage duplicate <src> <id>} (the same TypeArg mechanism as the
 * Stages window's "+ New Stage" row), which copies the stage, its parts, and their carriage
 * assignment entries server-side.
 */
public final class StageDuplicateNameScreen implements MenuScreen {

    private final String srcId;

    public StageDuplicateNameScreen(String srcId) {
        this.srcId = srcId;
    }

    @Override
    public String title() {
        return "Duplicate stage '" + srcId + "'";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Label("Copies the stage, its parts, and their carriage assignments."),
            new CommandMenuEntry.TypeArg("New stage id", "id",
                "dungeontrain editor stage duplicate " + srcId),
            new CommandMenuEntry.Back("Cancel"));
    }
}
