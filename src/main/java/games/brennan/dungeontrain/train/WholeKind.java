package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;

import java.util.Locale;

/**
 * The two sub-kinds of the editor's <b>Whole</b> section — what {@link WholeWeights} is keyed by.
 *
 * <ul>
 *   <li>{@link #ROOM} — one {@link WholeCarriage}: shell and interior in a single template, exactly
 *       one {@link CarriageDims} box. Placed into a train slot when the carriage roll lands on the
 *       {@code whole} shell entry (see {@code WholeCarriageSelection}).</li>
 *   <li>{@link #GROUP} — one {@link CarriageGroup}: a whole run of carriages, stamped over an
 *       entire carriage group when the group lottery says so (see {@code WholeGroupSelection}).</li>
 * </ul>
 *
 * <p>Each kind owns a bundled directory under {@code /data/dungeontrain/whole/<id>/} holding its
 * {@code .nbt} templates and a {@code weights.json}, and a user subdirectory that predates this
 * section and is kept for the installs and relay slugs that already use it.</p>
 */
public enum WholeKind {
    ROOM("room", WholeCarriageTemplateStore.SUBDIR),
    GROUP("group", CarriageGroupTemplateStore.SUBDIR);

    public static final String WEIGHTS_FILE = "weights.json";

    private final String id;
    private final String userSubdir;

    WholeKind(String id, String userSubdir) {
        this.id = id;
        this.userSubdir = userSubdir;
    }

    /** Stable lower-case token: the bundled directory name and the command word. */
    public String id() {
        return id;
    }

    /** The {@code config/dungeontrain/user/<subdir>} tree this kind's templates live in. */
    public String userSubdir() {
        return userSubdir;
    }

    /** Classpath prefix of this kind's bundled tier, with the trailing slash. */
    public String bundledResourcePrefix() {
        return "/data/dungeontrain/whole/" + id + "/";
    }

    /** Parse a command word back to a kind, case-insensitively; null when unknown. */
    public static WholeKind fromId(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (WholeKind k : values()) {
            if (k.id.equals(key)) return k;
        }
        return null;
    }
}
