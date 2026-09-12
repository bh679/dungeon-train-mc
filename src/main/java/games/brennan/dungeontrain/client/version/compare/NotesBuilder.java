package games.brennan.dungeontrain.client.version.compare;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides what the notes box shows for a run of releases, given what has loaded. Pure, so the
 * three-way fallback is pinned by tests rather than discovered in-game:
 *
 * <ul>
 *   <li>The ledger knows the release → its entries, filtered by tag; a release none of whose
 *       entries match is left out.</li>
 *   <li>The ledger has nothing for the release (a cascade tick, or older than the ledger) → the
 *       platform's own markdown, as before — but only while no filter is set, since there is
 *       nothing to match it on.</li>
 *   <li>No ledger at all (still loading, or the fetch failed) → the platform's markdown for every
 *       release, exactly the page's behaviour before tags existed.</li>
 * </ul>
 */
public final class NotesBuilder {

    private NotesBuilder() {}

    /** One section per release in the order given. */
    public static List<NotesSection> sections(List<ReleaseEntry> releases, @Nullable ChangelogLedger ledger,
                                              Set<ChangelogTag> filter) {
        List<NotesSection> out = new ArrayList<>();
        for (ReleaseEntry release : releases) {
            section(release, ledger, filter).ifPresent(out::add);
        }
        return out;
    }

    static Optional<NotesSection> section(ReleaseEntry release, @Nullable ChangelogLedger ledger,
                                                    Set<ChangelogTag> filter) {
        FullSemver version = release.version();
        if (ledger == null) {
            return Optional.of(NotesSection.forEntry(release));
        }
        if (!ledger.hasRelease(version)) {
            return filter.isEmpty() ? Optional.of(NotesSection.forEntry(release)) : Optional.empty();
        }
        List<LedgerEntry> matching = ledger.entriesReleasedIn(version).stream()
                .filter(e -> e.matches(filter))
                .toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NotesSection(Component.literal("v" + version),
                ChangelogLines.forLedgerEntries(version, matching)));
    }

    /**
     * How many ledger entries across {@code releases} carry each tag — the numbers on the filter
     * chips. Tags with no entries are absent, so the bar can leave them out.
     */
    public static Map<ChangelogTag, Integer> tagCounts(List<ReleaseEntry> releases, @Nullable ChangelogLedger ledger) {
        Map<ChangelogTag, Integer> counts = new EnumMap<>(ChangelogTag.class);
        if (ledger == null) return counts;
        for (ReleaseEntry release : releases) {
            for (LedgerEntry entry : ledger.entriesReleasedIn(release.version())) {
                for (ChangelogTag tag : entry.tags()) {
                    counts.merge(tag, 1, Integer::sum);
                }
            }
        }
        return counts;
    }
}
