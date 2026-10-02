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
        CARRIAGES, CONTENTS, PARTS, CHUNK_FRAMES, TRACKS,
        /** A building: a bare pad, or a copy of the one stood in. Dispatches {@code editor buildings enter}. */
        BUILDINGS,
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
        PORTAL_ROOM_SUB_VARIANT,
        /**
         * Whole room. Blank / Current, like {@link #CARRIAGES}; dispatches
         * {@code editor whole new <name> <source>}. No "Standard" — there is no built-in room to fall
         * back to.
         */
        WHOLE,
        /** Whole group — {@link #WHOLE}'s shape through {@code editor whole group new}. */
        WHOLE_GROUP
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
            case PARTS, CHUNK_FRAMES -> MenuLang.t("new_source.title_kind_source", kind);
            case BUILDINGS -> MenuLang.t("new_source.title_building");
            case CONTENTS -> kind.isEmpty() ? MenuLang.t("new_source.title_contents")
                : MenuLang.t("new_source.title_kind_source", sizeName(kind));
            case CARRIAGES -> MenuLang.t("new_source.title_carriage");
            // Tracks have no source picker today — only a name. Title still
            // matches the "New … — source" pattern so the screen reads
            // consistently with its siblings; the entry list collapses to
            // a single name TypeArg + Back below.
            case TRACKS -> MenuLang.t("new_source.title_kind_name", kind);
            case PORTALS -> MenuLang.t("new_source.title_portal");
            case CONTENTS_SUB_VARIANT, PORTAL_ROOM_SUB_VARIANT -> MenuLang.t("new_source.title_sub_variant", currentId);
            case WHOLE -> MenuLang.t("new_source.title_kind_source",
                MenuLang.typeName(games.brennan.dungeontrain.editor.EditorWholeTypeMenus.ROOM_TYPE_NAME));
            case WHOLE_GROUP -> MenuLang.t("new_source.title_kind_source",
                MenuLang.typeName(games.brennan.dungeontrain.editor.EditorWholeTypeMenus.GROUP_TYPE_NAME));
        };
    }

    @Override public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        switch (category) {
            case CARRIAGES -> {
                // A carriage is a Room, a Half (two to a group), or a group-long Full ("Group"),
                // each its own pool. From a pool's tab ({@code kind} is its size key) only that
                // pool's blank is offered; from anywhere else, all three.
                if ("flatbeds".equals(kind)) {
                    // A flatbed variant: a blank flatbed, or a copy of the one stood in. The pads between
                    // groups are cut from these, one per group, drawn by weight.
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.blank_flatbed"), "name", "dungeontrain editor new", "blank_flatbed"));
                    if (!currentId.isEmpty()) {
                        out.add(new CommandMenuEntry.TypeArg(
                            MenuLang.t("new_source.current", currentId), "name", "dungeontrain editor new", currentId));
                    }
                    break;
                }
                for (String size : List.of("room", "half", "full")) {
                    if (kind != null && !kind.isEmpty() && !kind.equals(size)) continue;
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.blank_size", sizeName(size)), "name", "dungeontrain editor new",
                        size.equals("room") ? "blank" : "blank_" + size));
                }
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", currentId), "name",
                        "dungeontrain editor new", currentId));
                }
                out.add(new CommandMenuEntry.TypeArg(
                    "Standard", "name", "dungeontrain editor new", "standard"));
            }
            case CONTENTS -> {
                // Only the selected size ({@code kind} is its key — Room when none is known): its
                // blank, a copy of the template stood in, and that size's standard template.
                String size = kind.isEmpty() ? "room" : kind;
                out.add(new CommandMenuEntry.TypeArg(
                    MenuLang.t("new_source.blank"), "name",
                    "dungeontrain editor contents new", size.equals("room") ? "blank" : "blank_" + size));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", currentId), "name",
                        "dungeontrain editor contents new", currentId));
                }
                String standard = standardContentsFor(size);
                if (standard != null) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.standard"), "name", "dungeontrain editor contents new", standard));
                }
            }
            case PARTS -> {
                String prefix = "dungeontrain editor part new " + kind;
                out.add(new CommandMenuEntry.TypeArg(
                    MenuLang.t("new_source.blank"), "name", prefix + " blank"));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", currentId), "name", prefix + " current"));
                }
                out.add(new CommandMenuEntry.TypeArg(
                    MenuLang.t("new_source.standard"), "name", prefix + " standard"));
            }
            case BUILDINGS -> {
                // A bare pad, or a copy of the building being stood in — `enter` with a new name makes
                // it; the optional trailing name is the building it is copied from.
                String prefix = "dungeontrain editor buildings enter";
                out.add(new CommandMenuEntry.TypeArg(MenuLang.t("new_source.blank"), "name", prefix));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", currentId), "name", prefix, currentId));
                }
            }
            case CHUNK_FRAMES -> {
                // Blank, or a copy of the frame being stood in. `enter` with a new name makes it;
                // the optional trailing name is the frame it is copied from.
                String prefix = "dungeontrain editor chunkframe enter";
                out.add(new CommandMenuEntry.TypeArg(MenuLang.t("new_source.blank"), "name", prefix));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", currentId), "name", prefix, currentId));
                }
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
                out.add(new CommandMenuEntry.TypeArg(MenuLang.t("new_source.blank"), "name", prefix, "blank"));
                if (!sourceId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", sourceId), "name", prefix, sourceId));
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
                        MenuLang.t("new_source.current", sourceId), "name", prefix, sourceId));
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.parent", currentId), "name", prefix, currentId));
                } else {
                    // Standing in the parent: the two rows would say the same thing.
                    out.add(new CommandMenuEntry.TypeArg(MenuLang.t("common.new"), "name", prefix));
                }
            }
            case WHOLE, WHOLE_GROUP -> {
                String prefix = category == Category.WHOLE_GROUP
                    ? "dungeontrain editor whole group new" : "dungeontrain editor whole new";
                out.add(new CommandMenuEntry.TypeArg(MenuLang.t("new_source.blank"), "name", prefix, "blank"));
                if (!currentId.isEmpty()) {
                    out.add(new CommandMenuEntry.TypeArg(
                        MenuLang.t("new_source.current", currentId), "name", prefix, currentId));
                }
            }
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    /**
     * The standard template a new contents of {@code sizeKey} copies: the built-in {@code default}
     * for a Room, the long corridor's {@code portal} for a Half, {@code default_full} for a Full.
     */
    static String standardContentsFor(String sizeKey) {
        return switch (sizeKey) {
            case "room" -> "default";
            case "half" -> "portal";
            case "full" -> "default_full";
            default -> null;
        };
    }

    /**
     * The contents size key a floating menu's type name stands for ({@code "Room"} → {@code room}),
     * or null for any other menu.
     */
    public static String contentsSizeKey(String typeName) {
        if (typeName == null) return null;
        return switch (typeName) {
            case "Room" -> "room";
            case "Half" -> "half";
            case "Full" -> "full";
            default -> null;
        };
    }

    /** The display name of a contents / carriage size, by its on-disk key. */
    static String sizeName(String key) {
        return switch (key) {
            case "half" -> MenuLang.t("size.half");
            case "full" -> MenuLang.t("size.full");
            default -> MenuLang.t("size.room");
        };
    }
}
