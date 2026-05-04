package games.brennan.dungeontrain.client.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * Three-option source picker shown when the user clicks "New" in the editor
 * menu. Replaces the immediate jump-to-typing flow so authors can decide what
 * the new model is seeded with before naming it.
 *
 * <p>Each option is a {@link CommandMenuEntry.TypeArg} that, on submit, runs
 * the matching {@code editor … new} verb with a source token in the command
 * suffix. {@link Category#PARTS} encodes the source token in the prefix
 * (because parts use a {@code <source> <name>} arg order while carriages and
 * contents put the source after the name).
 *
 * <p>"Current" is hidden when {@code currentId} is empty — i.e. the player is
 * not standing in a model the picker can copy from. "Standard" is always
 * shown; the server returns a chat error if no fallback can be resolved
 * (today this only happens for kinds with no bundled parts, e.g. roof).
 */
public final class NewSourcePickerScreen implements MenuScreen {

    public enum Category { CARRIAGES, CONTENTS, PARTS, TRACKS }

    private final Category category;
    private final String kind;
    private final String currentId;

    public NewSourcePickerScreen(Category category, String kind, String currentId) {
        this.category = category;
        this.kind = kind == null ? "" : kind;
        this.currentId = currentId == null ? "" : currentId;
    }

    @Override public String title() {
        return switch (category) {
            case PARTS -> "New " + kind + " — source";
            case CONTENTS -> "New contents — source";
            case CARRIAGES -> "New carriage — source";
            // Tracks have no source picker today — only a name. Title still
            // matches the "New … — source" pattern so the screen reads
            // consistently with its siblings; the entry list collapses to
            // a single name TypeArg + Back below.
            case TRACKS -> "New " + kind + " — name";
        };
    }

    @Override public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        switch (category) {
            case CARRIAGES -> {
                out.add(new CommandMenuEntry.TypeArg(
                    "Blank", "name", "dungeontrain editor new", "blank"));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        "Current (" + currentId + ")", "name",
                        "dungeontrain editor new", currentId));
                }
                out.add(new CommandMenuEntry.TypeArg(
                    "Standard", "name", "dungeontrain editor new", "standard"));
            }
            case CONTENTS -> {
                out.add(new CommandMenuEntry.TypeArg(
                    "Blank", "name", "dungeontrain editor contents new", "blank"));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        "Current (" + currentId + ")", "name",
                        "dungeontrain editor contents new", currentId));
                }
                out.add(new CommandMenuEntry.TypeArg(
                    "Standard", "name", "dungeontrain editor contents new", "default"));
            }
            case PARTS -> {
                String prefix = "dungeontrain editor part new " + kind;
                out.add(new CommandMenuEntry.TypeArg(
                    "Blank", "name", prefix + " blank"));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        "Current (" + currentId + ")", "name", prefix + " current"));
                }
                out.add(new CommandMenuEntry.TypeArg(
                    "Standard", "name", prefix + " standard"));
            }
            case TRACKS -> {
                // Tracks clone-from-current — single-row TypeArg matching
                // EditorMenuScreen.newEntryFor(tracks). The kind tag the
                // server expects (track / pillar_top / tunnel_section / …)
                // is in {@code kind}.
                out.add(new CommandMenuEntry.TypeArg(
                    "New", "name", "dungeontrain editor tracks new " + kind));
            }
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }
}
