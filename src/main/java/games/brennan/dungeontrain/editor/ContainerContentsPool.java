package games.brennan.dungeontrain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link ContainerContentsEntry} candidates plus a
 * {@code fillCount} cap that bounds how many of the container's slots get
 * rolled at spawn time.
 *
 * <p>{@code fillCount}:
 * <ul>
 *   <li>{@link #FILL_ALL} (-1) — every slot rolls (default; matches
 *       earliest behaviour).</li>
 *   <li>{@code 0} — no slots rolled, container spawns empty.</li>
 *   <li>{@code N > 0} — exactly {@code min(N, containerSize)} slots are
 *       picked deterministically and rolled; remaining slots stay empty.</li>
 * </ul>
 *
 * <p>Pool itself is immutable; mutations return a new pool.</p>
 */
public record ContainerContentsPool(List<ContainerContentsEntry> entries, int fillCount) {

    public static final int MAX_ENTRIES = 64;
    public static final int FILL_ALL = -1;

    public ContainerContentsPool {
        if (entries == null) entries = Collections.emptyList();
        else entries = List.copyOf(entries);
        if (fillCount < FILL_ALL) fillCount = FILL_ALL;
    }

    /** Two-arg overload defaulting to {@link #FILL_ALL}. */
    public ContainerContentsPool(List<ContainerContentsEntry> entries) {
        this(entries, FILL_ALL);
    }

    public static ContainerContentsPool empty() {
        return new ContainerContentsPool(Collections.emptyList(), FILL_ALL);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public int totalWeight() {
        int total = 0;
        for (ContainerContentsEntry e : entries) total += e.weight();
        return total;
    }

    public ContainerContentsPool added(ContainerContentsEntry entry) {
        List<ContainerContentsEntry> next = new ArrayList<>(entries.size() + 1);
        next.addAll(entries);
        if (next.size() < MAX_ENTRIES) next.add(entry);
        return new ContainerContentsPool(next, fillCount);
    }

    public ContainerContentsPool removed(int index) {
        if (index < 0 || index >= entries.size()) return this;
        List<ContainerContentsEntry> next = new ArrayList<>(entries);
        next.remove(index);
        return new ContainerContentsPool(next, fillCount);
    }

    public ContainerContentsPool replaced(int index, ContainerContentsEntry entry) {
        if (index < 0 || index >= entries.size()) return this;
        List<ContainerContentsEntry> next = new ArrayList<>(entries);
        next.set(index, entry);
        return new ContainerContentsPool(next, fillCount);
    }

    public ContainerContentsPool withFillCount(int newFillCount) {
        return new ContainerContentsPool(entries, newFillCount);
    }
}
