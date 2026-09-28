package games.brennan.dungeontrain.client.version.compare;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every version of the modpack one platform lists, newest first. Immutable: the fetch builds one
 * and hands it to {@link VersionCompareState}; the screen only reads.
 *
 * <p>The counting helpers answer the page's two sentences — "you are N versions behind" and
 * "CurseForge is N versions behind Modrinth" — by counting listed versions strictly newer than a
 * point, so the answer is in releases the player could actually install rather than in version
 * arithmetic (MINOR jumps by more than one when a release is skipped).</p>
 */
public record PlatformVersions(Platform platform, List<ReleaseEntry> entries) {

    public PlatformVersions {
        entries = entries.stream()
                .sorted(Comparator.comparing(ReleaseEntry::version).reversed())
                .toList();
    }

    public static PlatformVersions empty(Platform platform) {
        return new PlatformVersions(platform, List.of());
    }

    public Optional<ReleaseEntry> latest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    public Optional<ReleaseEntry> find(FullSemver version) {
        return entries.stream().filter(e -> e.version().equals(version)).findFirst();
    }

    /** How many listed versions are strictly newer than {@code version}. */
    public int countNewerThan(FullSemver version) {
        return (int) entries.stream().filter(e -> e.version().isNewerThan(version)).count();
    }

    /**
     * Listed versions newer than {@code exclusiveFrom} and no newer than {@code inclusiveTo},
     * newest first — the changelog a player reads to learn what they missed.
     */
    public List<ReleaseEntry> entriesBetween(FullSemver exclusiveFrom, FullSemver inclusiveTo) {
        return entries.stream()
                .filter(e -> e.version().isNewerThan(exclusiveFrom))
                .filter(e -> !e.version().isNewerThan(inclusiveTo))
                .toList();
    }
}
