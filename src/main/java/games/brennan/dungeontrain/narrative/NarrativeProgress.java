package games.brennan.dungeontrain.narrative;

import java.util.Set;
import java.util.TreeSet;

/**
 * Mutable per-(player, story) read state. Backed by a {@link TreeSet} of
 * 1-based letter indices the player has opened. Set semantics intentionally:
 * a player who reads out of order doesn't lose intermediate letters, and
 * {@link #nextUnreadLetter(int)} stays accurate either way.
 *
 * <p>Lives inside {@link NarrativeProgressData} — that owner persists the
 * state across save/load and calls {@link NarrativeProgressData#setDirty()}
 * after any mutation.</p>
 */
public final class NarrativeProgress {

    private final Set<Integer> readLetters;

    public NarrativeProgress() {
        this(new TreeSet<>());
    }

    public NarrativeProgress(Set<Integer> initial) {
        this.readLetters = new TreeSet<>(initial);
    }

    /** Snapshot of read letter indices, ascending. Safe to iterate. */
    public Set<Integer> readLetters() {
        return Set.copyOf(readLetters);
    }

    /**
     * Mark {@code letterIndex} (1-based) as read. Returns {@code true} when
     * this caused a state change (idempotent on repeat reads).
     */
    public boolean markRead(int letterIndex) {
        if (letterIndex < 1) return false;
        return readLetters.add(letterIndex);
    }

    /** Remove every letter from the read set. */
    public void clear() {
        readLetters.clear();
    }

    public boolean isComplete(int totalLetters) {
        if (totalLetters <= 0) return true;
        for (int i = 1; i <= totalLetters; i++) {
            if (!readLetters.contains(i)) return false;
        }
        return true;
    }

    public int readCount() {
        return readLetters.size();
    }

    /**
     * Lowest 1..{@code totalLetters} that isn't in the read set, or
     * {@code -1} if every letter is read.
     */
    public int nextUnreadLetter(int totalLetters) {
        for (int i = 1; i <= totalLetters; i++) {
            if (!readLetters.contains(i)) return i;
        }
        return -1;
    }
}
