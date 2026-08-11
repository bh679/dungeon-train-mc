package games.brennan.dungeontrain.builder;

import java.util.List;

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

    /**
     * A name that can become a template id: lower-case, no spaces or punctuation, matching every
     * id already on disk. Empty is not valid <em>here</em> — New treats an empty name as a draft
     * and never asks this, but the save that eventually names it does.
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
