package games.brennan.dungeontrain.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered list of {@link ContainerContentsEntry} candidates rolled per slot
 * at placement time by {@link ContainerContentsRoller}. Pool itself is
 * immutable; mutations return a new pool.
 */
public record ContainerContentsPool(List<ContainerContentsEntry> entries) {

    public static final int MAX_ENTRIES = 64;

    public ContainerContentsPool {
        if (entries == null) entries = Collections.emptyList();
        else entries = List.copyOf(entries);
    }

    public static ContainerContentsPool empty() {
        return new ContainerContentsPool(Collections.emptyList());
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
        return new ContainerContentsPool(next);
    }

    public ContainerContentsPool removed(int index) {
        if (index < 0 || index >= entries.size()) return this;
        List<ContainerContentsEntry> next = new ArrayList<>(entries);
        next.remove(index);
        return new ContainerContentsPool(next);
    }

    public ContainerContentsPool replaced(int index, ContainerContentsEntry entry) {
        if (index < 0 || index >= entries.size()) return this;
        List<ContainerContentsEntry> next = new ArrayList<>(entries);
        next.set(index, entry);
        return new ContainerContentsPool(next);
    }
}
