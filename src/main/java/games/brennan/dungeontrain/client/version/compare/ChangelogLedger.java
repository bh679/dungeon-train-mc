package games.brennan.dungeontrain.client.version.compare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The released entries of the changelog ledger, indexed by the release they shipped in. Immutable:
 * built once by the fetch and read by the page. Versions the ledger has no entry for — the
 * auto-release cascade's dependency-bump ticks, and releases from before the ledger existed — are
 * simply absent, and the page falls back to the platform's own notes for those.
 */
public final class ChangelogLedger {

    private final Map<FullSemver, List<LedgerEntry>> byRelease;

    public ChangelogLedger(List<LedgerEntry> entries) {
        Map<FullSemver, List<LedgerEntry>> grouped = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            grouped.computeIfAbsent(entry.releasedIn(), k -> new ArrayList<>()).add(entry);
        }
        Map<FullSemver, List<LedgerEntry>> frozen = new LinkedHashMap<>();
        grouped.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        this.byRelease = Map.copyOf(frozen);
    }

    public static ChangelogLedger empty() {
        return new ChangelogLedger(List.of());
    }

    public int size() {
        return byRelease.values().stream().mapToInt(List::size).sum();
    }

    /** Whether the ledger has anything to say about this release at all. */
    public boolean hasRelease(FullSemver release) {
        return byRelease.containsKey(release);
    }

    /**
     * Every entry that shipped in a release newer than {@code exclusiveFrom} and no newer than
     * {@code inclusiveTo}, newest release first, ledger order within a release.
     */
    public List<LedgerEntry> entriesReleasedBetween(FullSemver exclusiveFrom, FullSemver inclusiveTo) {
        return byRelease.entrySet().stream()
                .filter(e -> e.getKey().isNewerThan(exclusiveFrom) && !e.getKey().isNewerThan(inclusiveTo))
                .sorted(Map.Entry.<FullSemver, List<LedgerEntry>>comparingByKey().reversed())
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    /** The entries that shipped in {@code release}, in ledger (merge) order; empty when unknown. */
    public List<LedgerEntry> entriesReleasedIn(FullSemver release) {
        return byRelease.getOrDefault(release, List.of());
    }
}
