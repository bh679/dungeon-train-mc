package games.brennan.dungeontrain.narrative;

import java.util.Optional;

/**
 * The "moment" a welcome-lightning book is being rolled for. Picks the
 * sub-folder of {@code data/dungeontrain/narratives/starting_books/} the
 * registry rolls from. {@link #DEFAULT} maps to the top-level pool (and is
 * also the fallback for the other contexts when their folder is empty).
 *
 * <p>Resolution rules — at strike-fire time, not enqueue time — live in
 * {@code StartingBookEvents.resolveLoginContext}.</p>
 *
 * <ul>
 *   <li>{@link #DEFAULT} — first-ever play on this installation, OR fallback
 *       when a context-specific folder has no books authored yet.</li>
 *   <li>{@link #NEW_WORLD} — player has played the mod before (gamedir
 *       marker present) but this world has no other players welcomed yet.</li>
 *   <li>{@link #JOINED_WORLD} — first login on a world where at least one
 *       other player has already been welcomed (multiplayer feel).</li>
 *   <li>{@link #RESPAWN} — every (non-End-conquered) respawn.</li>
 * </ul>
 */
public enum StartingBookContext {

    /** Top-level pool — the existing flat folder. Also the fallback. */
    DEFAULT(""),

    NEW_WORLD("new_world"),
    JOINED_WORLD("joined_world"),
    RESPAWN("respawn");

    /** Folder name relative to {@code .../starting_books/}. Empty for DEFAULT. */
    private final String folderName;

    StartingBookContext(String folderName) {
        this.folderName = folderName;
    }

    /** Folder name under {@code .../starting_books/}; empty string for DEFAULT. */
    public String folderName() {
        return folderName;
    }

    /**
     * Parse a {@link StartingBookContext} from a user-supplied string (case
     * insensitive). Accepts the enum-name form ({@code "RESPAWN"}) or the
     * folder-name form ({@code "respawn"}, {@code "new_world"}, {@code "joined_world"},
     * {@code "default"}). Returns empty on no match.
     *
     * <p>Used by the {@code /narrative startingbook fire <context>} test
     * command's parser.</p>
     */
    public static Optional<StartingBookContext> fromString(String raw) {
        if (raw == null || raw.isEmpty()) return Optional.empty();
        String norm = raw.trim().toLowerCase();
        if (norm.equals("default")) return Optional.of(DEFAULT);
        for (StartingBookContext ctx : values()) {
            if (ctx.folderName.equals(norm)) return Optional.of(ctx);
            if (ctx.name().toLowerCase().equals(norm)) return Optional.of(ctx);
        }
        return Optional.empty();
    }
}
