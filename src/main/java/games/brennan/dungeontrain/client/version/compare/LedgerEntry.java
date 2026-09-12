package games.brennan.dungeontrain.client.version.compare;

import java.util.List;
import java.util.Set;

/**
 * One curated entry of the changelog ledger, as far as the Versions page needs it. {@code version}
 * is the mod version the merge shipped as (descriptive); {@code releasedIn} is the release tag it
 * actually reached players in, which is what the page groups by — several merges share one release.
 */
public record LedgerEntry(String id, FullSemver version, String type, String title, String summary,
                          List<String> highlights, Set<ChangelogTag> tags, FullSemver releasedIn) {

    public LedgerEntry {
        highlights = List.copyOf(highlights);
        tags = Set.copyOf(tags);
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
