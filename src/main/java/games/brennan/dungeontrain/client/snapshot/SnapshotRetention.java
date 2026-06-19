package games.brennan.dungeontrain.client.snapshot;

/**
 * Pure decision logic for "which ride photos must the gallery drop to honour its caps?".
 * Two bounds, applied oldest-first to a list ordered oldest&nbsp;&rarr;&nbsp;newest:
 *
 * <ol>
 *   <li><b>In-memory cap</b> ({@code maxInMemory}) &mdash; at most this many photos may sit in
 *       memory (unflushed, holding a GPU texture). Excess is covered by dropping the
 *       <em>oldest in-memory</em> photos. This is the graceful fallback on a machine that never
 *       gets a high-perf menu moment to offload: it behaves exactly like the old fixed ring.</li>
 *   <li><b>Total cap</b> ({@code maxOnDisk}) &mdash; at most this many photos may be retained at
 *       all (memory + disk). Excess is covered by discarding the <em>oldest overall</em> photos
 *       (a flushed photo's file, or an in-memory one). Clamped to be at least {@code maxInMemory}
 *       so it can never undercut the in-memory cap.</li>
 * </ol>
 *
 * <p>No Minecraft types: the caller passes one {@code onDisk} flag per photo (true = already
 * flushed to disk, so it costs no VRAM) and gets back the indices to remove, so this stays
 * unit-testable (mirrors {@link SnapshotPerformanceGate} / {@link SnapshotCooldowns}).</p>
 */
public final class SnapshotRetention {

    private SnapshotRetention() {}

    /**
     * @param onDisk      per-photo flushed flag, oldest&nbsp;&rarr;&nbsp;newest ({@code true} = on disk)
     * @param maxInMemory cap on in-memory (unflushed) photos; floored at 1
     * @param maxOnDisk   cap on total retained photos; raised to at least {@code maxInMemory}
     * @return the indices (ascending) to remove from the list to satisfy both caps; empty if none
     */
    public static int[] toRemove(boolean[] onDisk, int maxInMemory, int maxOnDisk) {
        int n = onDisk.length;
        int inMemCap = Math.max(1, maxInMemory);
        int totalCap = Math.max(inMemCap, maxOnDisk);

        boolean[] removed = new boolean[n];

        // 1) In-memory cap: drop the oldest unflushed photos beyond the cap.
        int inMemCount = 0;
        for (boolean d : onDisk) if (!d) inMemCount++;
        int dropInMem = Math.max(0, inMemCount - inMemCap);
        for (int i = 0; i < n && dropInMem > 0; i++) {
            if (!onDisk[i]) { removed[i] = true; dropInMem--; }
        }

        // 2) Total cap: discard the oldest remaining photos (flushed or not) beyond the cap.
        int remaining = n;
        for (boolean r : removed) if (r) remaining--;
        int discard = Math.max(0, remaining - totalCap);
        for (int i = 0; i < n && discard > 0; i++) {
            if (!removed[i]) { removed[i] = true; discard--; }
        }

        int count = 0;
        for (boolean r : removed) if (r) count++;
        int[] out = new int[count];
        int k = 0;
        for (int i = 0; i < n; i++) if (removed[i]) out[k++] = i;
        return out;
    }
}
