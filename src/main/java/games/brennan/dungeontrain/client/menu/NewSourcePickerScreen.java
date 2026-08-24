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

    public enum Category {
        CARRIAGES, CONTENTS, PARTS, TRACKS,
        /**
         * Portal pocket room. Same single-name shape as {@link #TRACKS} — no source choice — but
         * dispatches through the {@code portals} command prefix.
         */
        PORTALS,
        /**
         * Sub-variant of a contents group. {@code currentId} carries the <b>parent</b> id — a
         * sub-variant cannot itself be a parent, so the command always targets the group's root —
         * while {@code sourceId} carries the plot the player is actually standing in, which differs
         * when they are inside a sibling sub-variant. Dispatches
         * {@code editor contents group new <parent> <name> <source>} — atomic create +
         * add-to-group + teleport.
         */
        CONTENTS_SUB_VARIANT,
        /**
         * Sub-variant of a portal room. {@code currentId} carries the parent room, {@code sourceId}
         * the room the author is standing in. Same Current / Parent shape as
         * {@link #CONTENTS_SUB_VARIANT}, collapsing to a single row when the two are the same room.
         * Dispatches {@code editor portals group new <parent> <name> [source]}.
         */
        PORTAL_ROOM_SUB_VARIANT
    }

    private final Category category;
    private final String kind;
    private final String currentId;
    private final String sourceId;

    public NewSourcePickerScreen(Category category, String kind, String currentId) {
        this(category, kind, currentId, currentId);
    }

    /**
     * @param currentId the model the command targets — for {@link Category#CONTENTS_SUB_VARIANT},
     *                  the group's parent
     * @param sourceId  the model "Current" copies from: the plot the player is standing in, which is
     *                  the parent for every category except a sub-variant created from a sibling.
     *                  Falls back to {@code currentId}.
     */
    public NewSourcePickerScreen(Category category, String kind, String currentId, String sourceId) {
        this.category = category;
        this.kind = kind == null ? "" : kind;
        this.currentId = currentId == null ? "" : currentId;
        String src = sourceId == null || sourceId.isEmpty() ? this.currentId : sourceId;
        this.sourceId = src;
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
            case PORTALS -> "New dimensional carriage — name";
            case CONTENTS_SUB_VARIANT, PORTAL_ROOM_SUB_VARIANT -> "New sub-variant of " + currentId + " — name";
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
            case PORTALS -> {
                // Same single-row shape as TRACKS. The server seeds the new room from the
                // built-in geometry when nothing has been authored yet, so there is still no
                // source to choose between.
                out.add(new CommandMenuEntry.TypeArg(
                    "New", "name", "dungeontrain editor portals new " + kind));
            }
            case CONTENTS_SUB_VARIANT -> {
                // Same Blank / Current shape as CONTENTS. The parent is baked into the prefix; the
                // source token after the name decides what the new sub-variant is seeded with.
                // No "Standard" row: for contents that means the `default` built-in, which is not a
                // meaningful starting point for a variation on this particular parent.
                String prefix = "dungeontrain editor contents group new " + currentId;
                out.add(new CommandMenuEntry.TypeArg("Blank", "name", prefix, "blank"));
                if (!sourceId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        "Current (" + sourceId + ")", "name", prefix, sourceId));
                }
            }
            case PORTAL_ROOM_SUB_VARIANT -> {
                // Same Current / Parent shape as CONTENTS_SUB_VARIANT. The parent is baked into the
                // prefix; the source token after the name is the room to copy. No "Blank" row —
                // a portal room's empty state is the built-in geometry, which the server already
                // falls back to when the chosen source has nothing saved.
                String prefix = "dungeontrain editor portals group new " + currentId;
                if (!sourceId.isEmpty() && !sourceId.equals(currentId)) {
                    out.add(new CommandMenuEntry.TypeArg(
                        "Current (" + sourceId + ")", "name", prefix, sourceId));
                    out.add(new CommandMenuEntry.TypeArg(
                        "Parent (" + currentId + ")", "name", prefix, currentId));
                } else {
                    // Standing in the parent: the two rows would say the same thing.
                    out.add(new CommandMenuEntry.TypeArg("New", "name", prefix));
                }
            }
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }
}
