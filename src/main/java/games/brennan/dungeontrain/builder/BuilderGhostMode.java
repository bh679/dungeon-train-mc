package games.brennan.dungeontrain.builder;

import java.util.Locale;
import java.util.Optional;

/**
 * What the Train Builder does with the scenery around a build: the carriage shell, the flatbed pads
 * that cap the group, and the track the whole thing stands on.
 *
 * <p>All three are the same kind of thing — <b>context, not the build</b>. None of them is saved by
 * any template ({@code CarriageContentsPlacer} captures the interior box; {@code BuilderBounds}
 * deliberately leaves the pads and the track out of bounds), and all three used to stand there as
 * real blocks you could neither save nor safely touch. So they get one control between them.</p>
 *
 * <ul>
 *   <li>{@link #GHOST} — lifted out of the world and drawn as translucent textured ghosts. The
 *       default: you can see the shape of the train around you and see <em>through</em> it, and
 *       nothing you can reach is a block that will be thrown away.</li>
 *   <li>{@link #NONE} — lifted and not drawn. For judging a build with nothing else on screen.</li>
 *   <li>{@link #SOLID} — left standing as real blocks, which is what the builder did before this
 *       control existed. Walls you can stand on, build against, and light against.</li>
 * </ul>
 *
 * <p><b>Why this is world state and not a client preference.</b> The natural home for "what do I
 * want to look at" is a per-player config, and that is where the old two-state ghost toggle lived —
 * it only ever changed how the client drew blocks that were there either way. {@link #SOLID} breaks
 * that: it is the difference between a wall existing and not existing, which the server has to
 * decide and everyone in the world has to agree on. Trying to keep it per-player would mean one
 * player standing on a floor another can see through.</p>
 *
 * <p>Deliberately free of client-only imports so it stays unit-testable.</p>
 */
public enum BuilderGhostMode {

    GHOST("ghost"),
    NONE("none"),
    SOLID("solid");

    /** What a world that has never been told gets — see the class note on why ghosts are default. */
    public static final BuilderGhostMode DEFAULT = GHOST;

    private final String id;

    BuilderGhostMode(String id) {
        this.id = id;
    }

    /** Stable lower-case token — used in world data, packets and logs. */
    public String id() {
        return id;
    }

    /** Translation key for the pause-menu button's label in this state. */
    public String labelKey() {
        return "gui.dungeontrain.builder.ghost_train." + id;
    }

    /**
     * Whether this mode wants the scenery taken out of the world.
     *
     * <p>The one question {@code BuilderGhostCells} asks: {@link #GHOST} and {@link #NONE} differ
     * only in whether the client draws what was lifted, which is not the server's business.</p>
     */
    public boolean lifts() {
        return this != SOLID;
    }

    /** The next state on the pause-menu button, cycling in the order the enum declares. */
    public BuilderGhostMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Optional<BuilderGhostMode> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (BuilderGhostMode mode : values()) {
            if (mode.id.equals(needle)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /** As {@link #fromId}, falling back to {@link #DEFAULT} — for a world saved before this existed. */
    public static BuilderGhostMode orDefault(String raw) {
        return fromId(raw).orElse(DEFAULT);
    }
}
