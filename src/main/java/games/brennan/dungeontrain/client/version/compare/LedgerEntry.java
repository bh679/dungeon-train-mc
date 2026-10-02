package games.brennan.dungeontrain.client.version.compare;

import java.util.List;
import java.util.Set;

/**
 * One curated entry of the changelog ledger, as far as the Versions page needs it. {@code version}
 * is the mod version the merge shipped as (descriptive); {@code releasedIn} is the release tag it
 * actually reached players in, which is what the page groups by — several merges share one release.
 * {@code addresses} holds the player-reported issue ids the entry fixes ({@code lag},
 * {@code train_vanished}); ids this client does not know are kept and simply never matched.
 */
public record LedgerEntry(String id, FullSemver version, String type, String title, String summary,
                          List<String> highlights, Set<ChangelogTag> tags, FullSemver releasedIn,
                          Set<String> addresses) {

    public LedgerEntry {
        highlights = List.copyOf(highlights);
        tags = Set.copyOf(tags);
        addresses = Set.copyOf(addresses);
    }

    /** An entry that addresses no player-reported issue. */
    public LedgerEntry(String id, FullSemver version, String type, String title, String summary,
                       List<String> highlights, Set<ChangelogTag> tags, FullSemver releasedIn) {
        this(id, version, type, title, summary, highlights, tags, releasedIn, Set.of());
    }

    /** True when the entry carries any of {@code wanted}; an empty filter matches everything. */
    public boolean matches(Set<ChangelogTag> wanted) {
        if (wanted.isEmpty()) return true;
        for (ChangelogTag tag : wanted) {
            if (tags.contains(tag)) return true;
        }
        return false;
    }
}
