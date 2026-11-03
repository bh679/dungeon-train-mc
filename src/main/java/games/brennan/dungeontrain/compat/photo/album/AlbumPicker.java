package games.brennan.dungeontrain.compat.photo.album;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of a donor's albums a "your album" opens onto. Pure; package-private for tests.
 *
 * <p>The <em>unfinished</em> album is the lowest-numbered one that is not full — or, when every album
 * is full, a new one past the last, while the player's limit allows one (otherwise the last album).
 * A crafted or blank album is always the unfinished one. An album
 * found on the train is the unfinished one half the time; the other half is shared evenly among the
 * full ones, so old albums turn up again to be looked through.</p>
 */
final class AlbumPicker {

    /** The chance a found album is the unfinished one, when there are full ones to share the rest. */
    static final double UNFINISHED_SHARE = 0.5;

    private AlbumPicker() {}

    /** Every page holds a picture. */
    static boolean isFull(AlbumStore.Entry entry) {
        return entry.pages().stream().filter(p -> p.hash() != null).count() >= AlbumAdvancements.FULL;
    }

    /**
     * The lowest index under {@code limit} that is not full; when all of them are, one past the last if
     * the limit allows it, else the last album the player may have.
     */
    static int unfinished(List<Boolean> full, int limit) {
        int cap = Math.max(limit, 1);
        for (int i = 0; i < Math.min(full.size(), cap); i++) {
            if (!full.get(i)) return i;
        }
        return Math.min(full.size(), cap - 1);
    }

    /** The album a found "your album" opens onto, for a {@code roll} in [0, 1). Albums past {@code limit} are never picked. */
    static int found(List<Boolean> full, int limit, double roll) {
        List<Integer> fullOnes = new ArrayList<>();
        for (int i = 0; i < Math.min(full.size(), Math.max(limit, 1)); i++) {
            if (full.get(i)) fullOnes.add(i);
        }
        int unfinished = unfinished(full, limit);
        fullOnes.remove(Integer.valueOf(unfinished));   // at the limit, the "unfinished" album is a full one
        if (fullOnes.isEmpty() || roll < UNFINISHED_SHARE) return unfinished;
        double within = (roll - UNFINISHED_SHARE) / (1 - UNFINISHED_SHARE);
        return fullOnes.get(Math.min((int) (within * fullOnes.size()), fullOnes.size() - 1));
    }
}
