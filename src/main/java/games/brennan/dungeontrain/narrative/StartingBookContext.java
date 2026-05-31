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
 *   <li>{@link #NETHER} — first login of a run that <em>starts</em> in the
 *       Nether ({@code dungeon_train_nether} world type). Dimension-routed,
 *       so it takes precedence over the lifecycle contexts above for the
 *       welcome strike — see {@code StartingBookEvents.resolveLoginContext}.</li>
 *   <li>{@link #END} — first login of a run that <em>starts</em> in the End
 *       ({@code dungeon_train_end} world type). Same dimension-routed
 *       precedence as {@link #NETHER}.</li>
 * </ul>
 */
public enum StartingBookContext {

    /** Top-level pool — the existing flat folder. Also the fallback. */
    DEFAULT(""),

    NEW_WORLD("new_world"),
    JOINED_WORLD("joined_world"),
    RESPAWN("respawn"),

    /** Dimension-routed welcome pool for runs that start in the Nether. */
    NETHER("nether"),

    /** Dimension-routed welcome pool for runs that start in the End. */
    END("end");

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

    /**
     * Map a {@code StartingDimension} nbt id (the value returned by
     * {@code StartingDimension.nbtId()}) to the dimension-routed welcome
     * context that should fire on first login for a run started in that
     * dimension: {@code "the_nether"} → {@link #NETHER}, {@code "the_end"} →
     * {@link #END}. Everything else — including {@code "overworld"}, an
     * unknown id, or {@code null} — returns {@link Optional#empty()},
     * signalling "no dimension override; use the lifecycle context".
     *
     * <p>Keyed on the raw nbt-id string rather than the
     * {@code StartingDimension} enum so this class keeps its single
     * {@code java.util.Optional} import and stays unit-testable without
     * bootstrapping any Minecraft class. The two literals here mirror
     * {@code StartingDimension.NETHER.nbtId()} / {@code END.nbtId()}.</p>
     */
    public static Optional<StartingBookContext> forDimensionNbtId(String nbtId) {
        if (nbtId == null) return Optional.empty();
        return switch (nbtId) {
            case "the_nether" -> Optional.of(NETHER);
            case "the_end" -> Optional.of(END);
            default -> Optional.empty();
        };
    }
}
