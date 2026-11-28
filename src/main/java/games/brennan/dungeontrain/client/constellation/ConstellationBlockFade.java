package games.brennan.dungeontrain.client.constellation;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How hidden each block is, 0 (drawn as normal) to 1 (hidden whole). Once a block's flip is decided
 * ({@link ConstellationOcclusion}), it crosses over in {@link #SECONDS}, by time, never by distance.
 * A block that stops being in the way fades back in over the same time.
 */
final class ConstellationBlockFade {

    static final float SECONDS = 0.5f;

    private static volatile Map<ConstellationOcclusion.Hidden, Float> levels = Map.of();

    private ConstellationBlockFade() {}

    /** Every block still part hidden, with how far. */
    static Map<ConstellationOcclusion.Hidden, Float> levels() {
        return levels;
    }

    /** One frame on: toward 1 for the blocks in the way now, toward 0 for the rest. */
    static void advance(Collection<ConstellationOcclusion.Hidden> inTheWay, float dtSeconds) {
        levels = step(levels, inTheWay, dtSeconds);
    }

    static void clear() {
        levels = Map.of();
    }

    static Map<ConstellationOcclusion.Hidden, Float> step(Map<ConstellationOcclusion.Hidden, Float> from,
                                                          Collection<ConstellationOcclusion.Hidden> inTheWay,
                                                          float dtSeconds) {
        float d = Math.max(0f, dtSeconds) / SECONDS;
        Set<ConstellationOcclusion.Hidden> target = new HashSet<>(inTheWay);
        Map<ConstellationOcclusion.Hidden, Float> out = new LinkedHashMap<>();
        for (Map.Entry<ConstellationOcclusion.Hidden, Float> e : from.entrySet()) {
            boolean in = target.remove(e.getKey());
            float v = in ? Math.min(1f, e.getValue() + d) : e.getValue() - d;
            if (v > 0f) out.put(e.getKey(), v);
        }
        for (ConstellationOcclusion.Hidden h : target) out.put(h, Math.min(1f, d));
        return Map.copyOf(out);
    }
}
