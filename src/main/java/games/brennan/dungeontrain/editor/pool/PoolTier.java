package games.brennan.dungeontrain.editor.pool;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.train.ShellPool;
import games.brennan.dungeontrain.train.WholeKind;

import java.util.Optional;

/**
 * The size a template keeps across a pool move: one room, a corridor-length half, or a group of
 * this world's carriages end to end. Each pool spells the same three tiers its own way — this is
 * the one name for all three, and the box each tier occupies.
 */
public enum PoolTier {
    ROOM(ContentsSize.ROOM, WholeKind.ROOM, ShellPool.ROOM),
    HALF(ContentsSize.HALF, WholeKind.HALF, ShellPool.HALF),
    GROUP(ContentsSize.FULL, WholeKind.GROUP, ShellPool.GROUP);

    private final ContentsSize contentsSize;
    private final WholeKind wholeKind;
    private final ShellPool shellPool;

    PoolTier(ContentsSize contentsSize, WholeKind wholeKind, ShellPool shellPool) {
        this.contentsSize = contentsSize;
        this.wholeKind = wholeKind;
        this.shellPool = shellPool;
    }

    public ContentsSize contentsSize() {
        return contentsSize;
    }

    public WholeKind wholeKind() {
        return wholeKind;
    }

    public ShellPool shellPool() {
        return shellPool;
    }

    /** The full box (shell included) a template of this tier occupies; empty when this world cannot hold one. */
    public Optional<CarriageDims> box(CarriageDims dims, int groupSize) {
        return contentsSize.shellDims(dims, groupSize);
    }

    public static PoolTier of(ContentsSize size) {
        for (PoolTier t : values()) {
            if (t.contentsSize == size) return t;
        }
        return ROOM;
    }

    public static PoolTier of(WholeKind kind) {
        for (PoolTier t : values()) {
            if (t.wholeKind == kind) return t;
        }
        return ROOM;
    }

    /** The tier of a shell pool; empty for the flatbed pool, whose shells never move. */
    public static Optional<PoolTier> of(ShellPool pool) {
        for (PoolTier t : values()) {
            if (t.shellPool == pool) return Optional.of(t);
        }
        return Optional.empty();
    }
}
