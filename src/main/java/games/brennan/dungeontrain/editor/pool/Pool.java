package games.brennan.dungeontrain.editor.pool;

import java.util.Locale;
import java.util.Optional;

/**
 * The three template pools that describe the same carriage box in three roles: a <b>carriage</b>
 * shell the train rolls contents into, a <b>whole</b> carriage stamped verbatim (shell and interior),
 * and <b>contents</b> — the interior alone. A template moves between them without changing its
 * size tier ({@link PoolTier}); see {@link PoolMove}.
 */
public enum Pool {
    CARRIAGE("carriage"),
    WHOLE("whole"),
    CONTENTS("contents");

    private final String key;

    Pool(String key) {
        this.key = key;
    }

    /** The command token and lang-key suffix. */
    public String key() {
        return key;
    }

    /** {@code raw} as a pool, case-insensitively; empty for null / unknown. */
    public static Optional<Pool> parse(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toLowerCase(Locale.ROOT);
        for (Pool p : values()) {
            if (p.key.equals(k)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * Where a pool's cells sit relative to the carriage box: the shell and whole pools key their
     * sidecars from the box corner, contents from the interior corner one block in on every axis.
     */
    public int frameOffset() {
        return this == CONTENTS ? 1 : 0;
    }
}
