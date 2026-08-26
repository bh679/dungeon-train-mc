package games.brennan.dungeontrain.builder.structure;

import java.util.Locale;
import java.util.Optional;

/**
 * What the Train Builder does with the structures around a build.
 *
 * <ul>
 *   <li>{@link #GHOST} — drawn as translucent textured blocks, and not in the world. The default:
 *       you can see the shape of what the build sits in and see <em>through</em> it, and nothing you
 *       can reach is a block that will be thrown away.</li>
 *   <li>{@link #SOLID} — stood up as real blocks. Walls you can stand on, build against and light
 *       against. This is what the builder did before there was a control, for the pieces that had
 *       one.</li>
 *   <li>{@link #NONE} — neither drawn nor stood up. For judging a build with nothing else on
 *       screen.</li>
 * </ul>
 *
 * <h2>Why this is world state and not a client preference</h2>
 *
 * <p>The natural home for "what do I want to look at" is a per-player config, and that is where the
 * two-state ghost toggle this replaces lived — it only ever chose whether the client drew blocks
 * that were there either way. {@link #SOLID} breaks that: it is the difference between a wall
 * existing and not existing, which the server has to decide and everyone in the world has to agree
 * on. Kept per-player it would mean one player standing on a floor another can see through.</p>
 *
 * <p>Deliberately free of client-only imports so it stays unit-testable.</p>
 */
public enum BuilderStructureMode {

    GHOST("ghost"),
    SOLID("solid"),
    NONE("none");

    /** What a world that has never been told gets — see the class note on why ghosts are default. */
    public static final BuilderStructureMode DEFAULT = GHOST;

    private final String id;

    BuilderStructureMode(String id) {
        this.id = id;
    }

    /** Stable lower-case token — used in world data, packets and logs. */
    public String id() {
        return id;
    }

    /** Translation key for the pause-menu button's label in this state. */
    public String labelKey() {
        return "gui.dungeontrain.builder.structures." + id;
    }

    /** Whether the structures stand in the world as real blocks. */
    public boolean stamps() {
        return this == SOLID;
    }

    /** Whether the client draws them. The one question the renderer asks. */
    public boolean draws() {
        return this == GHOST;
    }

    /** The next state on the pause-menu button, cycling in the order the enum declares. */
    public BuilderStructureMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Optional<BuilderStructureMode> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (BuilderStructureMode mode : values()) {
            if (mode.id.equals(needle)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** As {@link #fromId}, falling back to {@link #DEFAULT} — for a world saved before this existed. */
    public static BuilderStructureMode orDefault(String raw) {
        return fromId(raw).orElse(DEFAULT);
    }
}
