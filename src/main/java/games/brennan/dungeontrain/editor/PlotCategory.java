package games.brennan.dungeontrain.editor;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The editor's <em>addressable</em> category vocabulary: which family of plots a
 * label row, menu tab, or click refers to.
 *
 * <p>This is deliberately a different question from the one {@link EditorCategory}
 * answers. {@code EditorCategory} is the <em>stamping</em> vocabulary — which set of
 * plots is currently built into the world — and parts are not their own set: they live
 * inside the CARRIAGES set, folded in by {@code EditorCategory.carriageModels()} and
 * reported as {@code CARRIAGES} by {@code EditorCategory.locate()}. But parts <em>are</em>
 * first-class to address: they have their own plots, their own type tabs, their own
 * visibility toggle, and their own {@code editor part enter} command. So the UI needs a
 * vocabulary of {@code EditorCategory} plus {@code PARTS}, which is this enum.
 * {@link #owner()} is the bridge back.</p>
 *
 * <p>Before this type existed the same concept travelled as a raw {@code String} in three
 * different cases at once — {@code "CARRIAGES"} on the wire, {@code "carriages"} in
 * commands and keyboard menus, {@code "Carriages"} in the status HUD — compared with
 * {@code switch} and {@code equals} across roughly thirty files. {@link #fromId(String)}
 * accepts all three, so a value's case stops deciding whether it routes.</p>
 *
 * <p><b>{@code "stages"} is not a member and must never become one.</b>
 * {@code EditorTypeMenus} writes that lowercase sentinel into the otherwise-uppercase
 * {@code Variant.category} field to mark the Stages panel's rows, which are not plots at
 * all. It is filtered out ahead of any category logic by the {@code isStagesMenu()}
 * check in {@code EditorTypeMenuInputHandler}. {@code fromId("stages")} returns empty, so
 * a stage row that ever reached a category switch would fall out harmlessly rather than
 * routing somewhere wrong.</p>
 */
public enum PlotCategory {
    /** Whole-carriage rooms — the WHOLE section's first row. */
    WHOLE(EditorCategory.WHOLE),
    /** Carriage groups — addressable in its own right, but stamped as part of {@link #WHOLE}. */
    WHOLE_GROUP(EditorCategory.WHOLE),
    /** Whole halves — the Half-box row of the Whole section, stamped as part of {@link #WHOLE}. */
    WHOLE_HALF(EditorCategory.WHOLE),
    CARRIAGES(EditorCategory.CARRIAGES),
    CONTENTS(EditorCategory.CONTENTS),
    TRACKS(EditorCategory.TRACKS),
    PORTALS(EditorCategory.PORTALS),
    BUILDINGS(EditorCategory.BUILDINGS),
    /** The official Lost City buildings — view-only, browsed and stamped with {@link #BUILDINGS}. */
    LOST_CITY(EditorCategory.BUILDINGS),
    /** Staged relay builds — the Workbench's own, placed plots; see {@code WorkbenchEditor}. */
    WORKBENCH(EditorCategory.WORKBENCH),
    ARCHITECTURE(EditorCategory.ARCHITECTURE),
    /** Carriage parts — addressable in its own right, but stamped as part of {@link #CARRIAGES}. */
    PARTS(EditorCategory.CARRIAGES),
    /** Chunk frames — the one template that dresses a dimensional carriage room, browsed under Dimensions. */
    CHUNK_FRAMES(EditorCategory.PORTALS);

    private static final PlotCategory[] VALUES = values();

    private final EditorCategory owner;

    PlotCategory(EditorCategory owner) {
        this.owner = owner;
    }

    /**
     * The plot set this category is stamped as part of. Identity for everything except
     * {@link #PARTS}, which reports {@link EditorCategory#CARRIAGES} — matching where
     * {@code EditorCategory.locate()} puts a part plot.
     */
    public EditorCategory owner() {
        return owner;
    }

    /** Stable lower-case token, and the same string {@code /dt editor <token>} uses. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Human-readable label for the status HUD. Kept in step with {@link EditorCategory#displayName()}. */
    public String displayName() {
        return switch (this) {
            case PARTS -> "Parts";
            case CHUNK_FRAMES -> "Frames";
            case WHOLE_GROUP -> "Group";
            case WHOLE_HALF -> "Half";
            default -> owner.displayName();
        };
    }

    /**
     * Whether a group of this category is shown when the screen is filtered to {@code page}: the
     * page itself, or a sibling stamped as part of the same section (parts under carriages, groups
     * under whole). Replaces the hand-written {@code CARRIAGES && PARTS} folds.
     */
    public boolean browsesUnder(PlotCategory page) {
        return page != null && owner == page.owner();
    }

    /**
     * Parse any of the three case conventions this concept has travelled in back to a
     * category. Lenient by design and never throws: {@code null}, blank, the
     * {@code "stages"} sentinel, and anything else unrecognised all yield empty, which
     * every call site treats as "no category, do nothing".
     */
    public static Optional<PlotCategory> fromId(String raw) {
        if (raw == null) return Optional.empty();
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return Optional.empty();
        for (PlotCategory c : VALUES) {
            if (c.id().equals(key)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Widen a stamping category to its addressable counterpart. Total — never yields {@link #PARTS} or {@link #WHOLE_GROUP}. */
    public static PlotCategory of(EditorCategory category) {
        return valueOf(category.name());
    }

    /**
     * Whether a plot in this category draws the Save / Reset / Clear row, and correspondingly
     * whether the server accepts an {@code EditorPlotActionPacket} for it.
     *
     * <p>False for {@link #PARTS}: the part commands are position-resolved
     * ({@code runPartSave} reads the plot the player stands in) rather than addressed by
     * {@code (modelId, modelName)} like the others, so a part has no action row to draw.
     * This predicate is the single fact both the renderer and the packet handler consult —
     * previously the renderer's allowlist was the only thing keeping a PARTS action away
     * from a handler that would have dropped it.</p>
     */
    public boolean hasActionRow() {
        return this != PARTS && this != CHUNK_FRAMES && this != ARCHITECTURE && this != LOST_CITY;
    }

    /** Whether templates here have a spawn-weight pool to bump. False for parts and architecture. */
    public boolean hasWeightPool() {
        return this != PARTS && this != CHUNK_FRAMES && this != ARCHITECTURE && this != LOST_CITY && this != WORKBENCH;
    }

    /** Whether templates here carry a spawn gate — min/max level, dimensions, stage link. */
    public boolean hasGate() {
        return this != PARTS && this != CHUNK_FRAMES && this != ARCHITECTURE && this != BUILDINGS && this != LOST_CITY
            && this != WORKBENCH;
    }

    /**
     * Whether a plot here is an author-sized room box — the dimension steppers, mode, copies,
     * sky, exits and doorwall rows. Portal rooms only; nothing else has walls to decide about.
     */
    public boolean hasRoomBox() {
        return this == PORTALS;
    }

    /** Whether rows here carry the leading per-plot visibility checkbox. Parts only. */
    public boolean hasVisibilityToggle() {
        return this == PARTS;
    }

    // ---- the command table (OOP review §4) ----

    /** The per-template verbs the client menus send; see {@link #command}. */
    public enum Verb {
        /** {@code … weight <addr>} — the row's number prefix; {@code inc}/{@code dec}/a value follow. */
        WEIGHT,
        /** {@code … phase <addr>} — {@code <phase> on|off|others} follows. */
        PHASE,
        /** {@code … minlevel|maxlevel <addr>} — the sub word is spliced in by {@link #levelCommand}. */
        LEVEL,
        /** Teleport to the plot. The track-side kinds are model-shaped and answer empty here. */
        ENTER,
        /** Delete the template's file: {@code reset}, {@code delete} or {@code remove} by kind. */
        REMOVE,
        /** Set the display label ({@code label}), or rename where the kind has no label. */
        LABEL,
        /** {@code … builder <addr>} — {@code <uuid> [name]} follows. */
        BUILDER,
        /** {@code … new <kind>} — the typed name follows. Only the kinds whose New is a command, not a picker. */
        NEW,
        /** {@code dungeontrain editor clear} — the plot the player stands in; no address. */
        CLEAR
    }

    /** How a category's commands name the template: by id, by id then name, by name, by part kind then name, or not at all. */
    private enum Address { ID, ID_NAME, NAME, KIND_NAME, NONE }

    /**
     * One category's row of the table: the command root every verb hangs off, how the template is
     * addressed, and the verb words that differ from the verb's own name.
     */
    private record Commands(String root, Address address, Map<Verb, String> words, boolean clear) {
        static final String EDITOR = "dungeontrain editor";

        static Commands of(String root, Address address, Verb... verbs) {
            Map<Verb, String> words = new java.util.EnumMap<>(Verb.class);
            for (Verb v : verbs) words.put(v, v.name().toLowerCase(Locale.ROOT));
            return new Commands(root, address, words, false);
        }

        /** The same row with {@code verb} spelled {@code word} on the wire. */
        Commands word(Verb verb, String word) {
            Map<Verb, String> words = new java.util.EnumMap<>(this.words);
            words.put(verb, word);
            return new Commands(root, address, words, clear);
        }

        Commands clearable() {
            return new Commands(root, address, words, true);
        }
    }

    private static final String ROOT = Commands.EDITOR;
    private static final String RESET = "reset";
    private static final String DELETE = "delete";
    private static final String GOTO = "goto";

    /**
     * The whole table, one row per category. Every string a client menu sends for a template is
     * built from this row, so the seven screens that used to each spell their own copy of it
     * cannot disagree — the two that did (TRACKS weight without a name; {@code portals reset}
     * with and without one) are settled here: the track-side verbs take {@code <kind> <name>},
     * always, and a portal room is removed by name.
     */
    private Commands commands() {
        return switch (this) {
            case CARRIAGES -> Commands.of(ROOT, Address.ID,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.ENTER, Verb.LABEL, Verb.BUILDER)
                    .word(Verb.REMOVE, RESET).clearable();
            case CONTENTS -> Commands.of(ROOT + " contents", Address.ID,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.ENTER, Verb.LABEL, Verb.BUILDER)
                    .word(Verb.REMOVE, RESET).clearable();
            case WHOLE -> Commands.of(ROOT + " whole", Address.ID,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.ENTER, Verb.LABEL, Verb.BUILDER)
                    .word(Verb.REMOVE, RESET).clearable();
            case WHOLE_GROUP -> Commands.of(ROOT + " whole group", Address.ID,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.ENTER, Verb.LABEL, Verb.BUILDER)
                    .word(Verb.REMOVE, RESET).clearable();
            case WHOLE_HALF -> Commands.of(ROOT + " whole half", Address.ID,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.ENTER, Verb.LABEL, Verb.BUILDER)
                    .word(Verb.REMOVE, RESET).clearable();
            // Track-side verbs address <kind> <name>. ENTER is model-shaped (track / pillar / tunnel
            // each have their own command) and is answered per Template, not here. REMOVE is the
            // standing form — the plot the player is in (see addressFor) — and there is no single
            // plot to Clear: a kind's row holds every named variant.
            case TRACKS -> Commands.of(ROOT + " tracks", Address.ID_NAME,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.NEW)
                    .word(Verb.REMOVE, RESET);
            // A room is addressed as <kind> <name> for every verb, enter by name alone (one kind).
            case PORTALS -> Commands.of(ROOT + " portals", Address.ID_NAME,
                    Verb.WEIGHT, Verb.PHASE, Verb.LEVEL, Verb.ENTER, Verb.LABEL, Verb.BUILDER, Verb.NEW)
                    .word(Verb.REMOVE, RESET).clearable();
            case BUILDINGS -> Commands.of(ROOT + " buildings", Address.NAME, Verb.WEIGHT)
                    .word(Verb.ENTER, GOTO).word(Verb.REMOVE, DELETE).clearable();
            case LOST_CITY -> Commands.of(ROOT + " buildings lostcity", Address.NAME)
                    .word(Verb.ENTER, GOTO);
            case CHUNK_FRAMES -> Commands.of(ROOT + " chunkframe", Address.NAME, Verb.ENTER)
                    .word(Verb.REMOVE, DELETE).word(Verb.LABEL, "rename").clearable();
            case WORKBENCH -> Commands.of(ROOT + " workbench", Address.NAME)
                    .word(Verb.ENTER, GOTO).word(Verb.REMOVE, "remove").clearable();
            case PARTS -> Commands.of(ROOT + " part", Address.KIND_NAME, Verb.ENTER)
                    .word(Verb.REMOVE, RESET).clearable();
            case ARCHITECTURE -> Commands.of(ROOT, Address.NONE);
        };
    }

    /**
     * The slash command (no leading slash) that {@code verb} sends for the template
     * {@code (modelId, modelName)} in this category, or empty when the category has no such verb
     * or the address it needs is missing — a track-side verb without a name, a frame without one.
     *
     * <p>{@code modelId} is the bare command token ({@code standard}, {@code portal_room},
     * {@code pillar_top}, a part kind); {@code modelName} the variant name where the kind has one.
     * The caller appends what follows the address ({@code inc}, a phase token, a typed name).</p>
     */
    public Optional<String> command(Verb verb, String modelId, String modelName) {
        Commands row = commands();
        if (verb == Verb.CLEAR) return row.clear() ? Optional.of(ROOT + " clear") : Optional.empty();
        String word = row.words().get(verb);
        if (word == null) return Optional.empty();
        Address address = addressFor(row, verb);
        return address(address, modelId, modelName).map(addr -> row.root() + " " + word + addr);
    }

    /** {@link #command} for {@link Verb#LEVEL}, with the bound ({@code minlevel} / {@code maxlevel}) spliced in. */
    public Optional<String> levelCommand(String sub, String modelId, String modelName) {
        Commands row = commands();
        if (!row.words().containsKey(Verb.LEVEL) || sub == null || sub.isEmpty()) return Optional.empty();
        return address(row.address(), modelId, modelName).map(addr -> row.root() + " " + sub + addr);
    }

    /**
     * The address a verb takes: the row's, except that a portal room is entered by name alone
     * (one kind, so the kind token says nothing), New names the kind alone (the typed name
     * follows it), and a track-side Remove is the standing form.
     */
    private Address addressFor(Commands row, Verb verb) {
        if (verb == Verb.NEW) return Address.ID;
        if (this == PORTALS && verb == Verb.ENTER) return Address.NAME;
        // `tracks reset <kind>` acts on the plot the player stands in: the Tracks editor's rows
        // are kinds, and the active variant of one is what Remove means there.
        if (this == TRACKS && verb == Verb.REMOVE) return Address.ID;
        return row.address();
    }

    /** The address part, with its leading space, or empty when what the shape needs is missing. */
    private static Optional<String> address(Address address, String modelId, String modelName) {
        boolean id = modelId != null && !modelId.isEmpty();
        boolean name = modelName != null && !modelName.isEmpty();
        return switch (address) {
            case ID -> id ? Optional.of(" " + modelId) : Optional.empty();
            case ID_NAME, KIND_NAME -> id && name ? Optional.of(" " + modelId + " " + modelName) : Optional.empty();
            case NAME -> name ? Optional.of(" " + modelName) : Optional.empty();
            case NONE -> Optional.of("");
        };
    }
}
