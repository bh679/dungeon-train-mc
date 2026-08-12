package games.brennan.dungeontrain.builder;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What the Train Builder's <b>New</b> screen offers for a given selection.
 *
 * <p>Pure, and in the common package rather than beside the screen: the same table decides what the
 * picker lists <em>and</em> what {@code BuilderNewPacket} does with the id that comes back, so
 * server code has to be able to read it without importing a client class.</p>
 *
 * <p>Separate from the screen so the control logic can be tested without a client.
 * Create no longer assembles an editor command — it sends {@code BuilderNewPacket} and the server
 * stamps the world directly, the same path the Train Builder tiles take.</p>
 *
 * <p>There is no <em>Default vs Copy</em> choice: every selection has exactly one picker, and its
 * first entry <em>is</em> the default. Asking which of the two you wanted was a question you had to
 * answer before you could answer the real one, and it doubled the lists the screen had to
 * explain.</p>
 */
public final class BuilderNewOptions {

    private BuilderNewOptions() {}

    /** What you're authoring. Decides every control below it on the screen. */
    public enum SubType {
        PARTS("parts"),
        WHOLE_CARRIAGE("whole_carriage"),
        CARRIAGE_ROOM("carriage_room");

        private final String id;

        SubType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String labelKey() {
            return "gui.dungeontrain.builder.new.subtype." + id;
        }

        /**
         * The sub type behind a stored id, or empty when there isn't one.
         *
         * <p>The read side of {@code DungeonTrainWorldData.builderSubType()}, which keeps the id
         * string rather than the enum. Empty for a world that has never had a New or an Open run in
         * it — that is a real state, not a corrupt one, and callers are expected to have a default
         * for it.</p>
         */
        public static java.util.Optional<SubType> fromId(String raw) {
            if (raw == null) {
                return java.util.Optional.empty();
            }
            String needle = raw.trim().toLowerCase(java.util.Locale.ROOT);
            for (SubType value : values()) {
                if (value.id.equals(needle)) {
                    return java.util.Optional.of(value);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * Which registry the single picker lists — and therefore what the picked id means to the
     * server when it stamps.
     */
    public enum CopySource {
        /** Named gate presets. Picking one copies a carriage that belongs to that stretch of the game. */
        STAGES,
        /** Carriage shell templates. */
        CARRIAGES,
        /** Carriage contents templates. */
        CONTENTS,
        /** Part templates of the separately-chosen {@link games.brennan.dungeontrain.train.CarriagePartKind}. */
        PARTS,
        /** Nothing to pick — the mode authors its thing outright. */
        NONE
    }

    /** {@code CarriagePartKind} values, lower-cased to match their template ids. */
    public static final List<String> PART_KINDS = List.of("floor", "walls", "roof", "doors");

    /**
     * The track modes author track tiles and tunnel portals outright — no sub type, no picker.
     * Every control between the mode row and Create is hidden for them.
     */
    public static boolean hasSubTypes(BuilderMode mode) {
        return mode == BuilderMode.TRAIN_OUTSIDE || mode == BuilderMode.INSIDE_CARRIAGE;
    }

    /**
     * What this mode lets you author, in the order the picker cycles them.
     *
     * <p><b>Whole Carriage is a Train Outside answer only.</b> From inside, the carriage shell is the
     * room you are standing in — the thing around the work, not the work — so offering it there asks
     * you to edit the walls from a position where you can only see their inside faces. What you
     * author from inside is the room and the parts that dress it.</p>
     *
     * <p>A restriction on what the screens <em>offer</em>, deliberately not a new invariant. Mode and
     * sub type are persisted as independent strings in {@code DungeonTrainWorldData}, so a world
     * saved before this can hold Inside Carriage + Whole Carriage; every server-side reader still
     * answers for that pair, because turning a stale world into a failure would be a far worse trade
     * than showing one combination the picker no longer proposes.</p>
     */
    public static List<SubType> subTypesFor(BuilderMode mode) {
        if (!hasSubTypes(mode)) {
            return List.of();
        }
        return mode == BuilderMode.INSIDE_CARRIAGE
                ? List.of(SubType.CARRIAGE_ROOM, SubType.PARTS)
                : List.of(SubType.WHOLE_CARRIAGE, SubType.CARRIAGE_ROOM, SubType.PARTS);
    }

    /**
     * What this mode starts on, and what an out-of-range selection falls back to.
     *
     * <p>Both jobs, because they are the same question: the screens carry one sub type across a mode
     * change, and a mode that cannot show the carried value has to land somewhere. Answering
     * {@link SubType#WHOLE_CARRIAGE} for a mode with no sub types at all keeps the old field
     * initialiser's behaviour for the track modes, whose controls are hidden either way.</p>
     */
    public static SubType defaultSubTypeFor(BuilderMode mode) {
        List<SubType> offered = subTypesFor(mode);
        return offered.isEmpty() ? SubType.WHOLE_CARRIAGE : offered.get(0);
    }

    /** The selection to hold after a mode change: the current one when it survives, else the default. */
    public static SubType clampSubType(BuilderMode mode, SubType current) {
        return current != null && subTypesFor(mode).contains(current)
                ? current
                : defaultSubTypeFor(mode);
    }

    /**
     * What the picker lists, which depends on the mode <em>and</em> the sub type — "a room" means a
     * different thing depending on whether you came in from outside the train or inside it:
     *
     * <ul>
     *   <li><b>Whole Carriage</b> — stages. A builder thinks "a carriage for the desert stretch",
     *       not "a copy of {@code windowed}", so the list is the stretches of the game.</li>
     *   <li><b>Parts</b> — the part templates of whichever kind is picked beside the list.</li>
     *   <li><b>Carriage Room</b> — from outside, which carriage the room belongs to; from inside,
     *       an existing room to copy.</li>
     * </ul>
     */
    public static CopySource copySourceFor(BuilderMode mode, SubType subType) {
        if (!hasSubTypes(mode)) {
            return CopySource.NONE;
        }
        return switch (subType) {
            case WHOLE_CARRIAGE -> CopySource.STAGES;
            case PARTS -> CopySource.PARTS;
            case CARRIAGE_ROOM -> mode == BuilderMode.INSIDE_CARRIAGE
                    ? CopySource.CONTENTS
                    : CopySource.CARRIAGES;
        };
    }

    /** Whether a part-kind control belongs beside the picker for this selection. */
    public static boolean showsPartKind(BuilderMode mode, SubType subType) {
        return copySourceFor(mode, subType) == CopySource.PARTS;
    }

    // ---- Tagged picks ----

    /**
     * Which list a picked value came out of. The Whole Carriage picker shows two: the Stage presets
     * first, then whole carriages already saved. Both are things you can start a build from, and
     * they mean entirely different work to the server — a stage decides which parts get stamped
     * onto a fresh shell, a saved build gets stamped back verbatim.
     */
    public enum PickKind {
        /** A named gate preset from {@code StageStore}. */
        STAGE,
        /** A saved whole carriage from {@code WholeCarriageRegistry}. */
        WHOLE_CARRIAGE
    }

    /** One resolved picker value: which list it came from, and the bare id within that list. */
    public record Pick(PickKind kind, String id) {
        public Pick {
            id = id == null ? "" : id;
        }

        public boolean isEmpty() {
            return id.isEmpty();
        }
    }

    private static final String WHOLE_CARRIAGE_TAG = "whole:";

    /**
     * Tag {@code id} as a saved whole carriage for transport through the picker and the New packet.
     *
     * <p>Tagged rather than bare because an id is only unique within its own store, and they do
     * collide across stores — the picker's own label code already has to work around {@code quartz}
     * being both a Stage and one of {@code maze}'s sub-variants. Two lists in one control makes
     * that a correctness problem rather than a labelling one: an untagged {@code quartz} coming
     * back from the client is genuinely ambiguous, and guessing wrong stamps the wrong thing.</p>
     */
    public static String tagWholeCarriage(String id) {
        return WHOLE_CARRIAGE_TAG + id;
    }

    /** Tag {@code id} as a Stage. Stages are the untagged default — this exists for symmetry. */
    public static String tagStage(String id) {
        return id;
    }

    /**
     * Read a picker value back into the list it came from.
     *
     * <p>Untagged values parse as Stages, which is what every value was before whole carriages had
     * their own list — so a client that predates the tag, or a stage id typed in by hand, still
     * lands in the arm it always did.</p>
     */
    public static Pick parsePick(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Pick(PickKind.STAGE, "");
        }
        if (raw.startsWith(WHOLE_CARRIAGE_TAG)) {
            return new Pick(PickKind.WHOLE_CARRIAGE, raw.substring(WHOLE_CARRIAGE_TAG.length()));
        }
        return new Pick(PickKind.STAGE, raw);
    }

    /**
     * The one shape every template identity uses — {@code CarriageVariant.NAME_PATTERN} and the
     * contents, whole-carriage and part patterns are all this, and a Save turns the name into
     * whichever of them the sub type calls for. Held in lockstep by {@code BuilderNewOptionsTest}:
     * the two drifting apart is a crash at Save rather than a message on the screen that asked for
     * the name.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    /**
     * A name that can become a template id: lower-case, no spaces or punctuation, matching every
     * id already on disk. Empty is not valid <em>here</em> — New treats an empty name as a draft
     * and never asks this, but the save that eventually names it does.
     */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }
}
