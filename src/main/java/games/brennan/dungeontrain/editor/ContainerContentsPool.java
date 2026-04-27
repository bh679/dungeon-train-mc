package games.brennan.dungeontrain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link ContainerContentsEntry} candidates plus a
 * {@code fillMin}/{@code fillMax} range that bounds how many of the
 * container's slots get rolled at spawn time.
 *
 * <p>Roll order:
 * <ol>
 *   <li>{@code K = random in [fillMin, effectiveMax]} (inclusive), where
 *       {@code effectiveMax = fillMax == FILL_ALL ? containerSize : min(fillMax, containerSize)}.</li>
 *   <li>{@link ContainerContentsRoller} picks {@code K} slot indices via
 *       seeded Fisher–Yates and rolls the weighted pool for each.</li>
 *   <li>For each picked slot, the entry's stack count is rolled in
 *       {@code [1, entry.count()]} — entry count is a *maximum*, not an
 *       absolute.</li>
 * </ol>
 *
 * <p>Defaults: {@code fillMin = 0}, {@code fillMax = FILL_ALL} (every
 * slot rolls). Invariant maintained at edit time: {@code fillMin ≤ fillMax}
 * (when {@code fillMax != FILL_ALL}).</p>
 */
public record ContainerContentsPool(List<ContainerContentsEntry> entries, int fillMin, int fillMax) {

    public static final int MAX_ENTRIES = 64;
    /** Sentinel for {@code fillMax}: "fill up to the container's full slot count". */
    public static final int FILL_ALL = -1;
    public static final int MAX_FILL_BOUND = 64;

    public ContainerContentsPool {
        if (entries == null) entries = Collections.emptyList();
        else entries = List.copyOf(entries);
        if (fillMin < 0) fillMin = 0;
        if (fillMin > MAX_FILL_BOUND) fillMin = MAX_FILL_BOUND;
        if (fillMax < FILL_ALL) fillMax = FILL_ALL;
        if (fillMax > MAX_FILL_BOUND) fillMax = MAX_FILL_BOUND;
        // Enforce min ≤ max (FILL_ALL is treated as +∞).
        if (fillMax != FILL_ALL && fillMin > fillMax) fillMin = fillMax;
    }

    /** Default-range overload — min 0, max FILL_ALL. */
    public ContainerContentsPool(List<ContainerContentsEntry> entries) {
        this(entries, 0, FILL_ALL);
    }

    public static ContainerContentsPool empty() {
        return new ContainerContentsPool(Collections.emptyList(), 0, FILL_ALL);
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }

    public int totalWeight() {
        int total = 0;
        for (ContainerContentsEntry e : entries) total += e.weight();
        return total;
    }

    public boolean isDefaultRange() {
        return fillMin == 0 && fillMax == FILL_ALL;
    }

    public ContainerContentsPool added(ContainerContentsEntry entry) {
        List<ContainerContentsEntry> next = new ArrayList<>(entries.size() + 1);
        next.addAll(entries);
        if (next.size() < MAX_ENTRIES) next.add(entry);
        return new ContainerContentsPool(next, fillMin, fillMax);
    }

    public ContainerContentsPool removed(int index) {
        if (index < 0 || index >= entries.size()) return this;
        List<ContainerContentsEntry> next = new ArrayList<>(entries);
        next.remove(index);
        return new ContainerContentsPool(next, fillMin, fillMax);
    }

    public ContainerContentsPool replaced(int index, ContainerContentsEntry entry) {
        if (index < 0 || index >= entries.size()) return this;
        List<ContainerContentsEntry> next = new ArrayList<>(entries);
        next.set(index, entry);
        return new ContainerContentsPool(next, fillMin, fillMax);
    }

    public ContainerContentsPool withFillMin(int newMin) {
        return new ContainerContentsPool(entries, newMin, fillMax);
    }

    public ContainerContentsPool withFillMax(int newMax) {
        return new ContainerContentsPool(entries, fillMin, newMax);
    }
}
