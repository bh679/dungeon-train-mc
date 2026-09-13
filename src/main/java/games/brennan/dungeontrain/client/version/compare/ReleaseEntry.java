package games.brennan.dungeontrain.client.version.compare;

import javax.annotation.Nullable;

/**
 * One published modpack version on one platform. {@code changelog} is the curated markdown the
 * release pipeline attaches to every upload; CurseForge's keyless mirror does not expose it, so
 * entries from there carry {@code null} and borrow Modrinth's text for the same version.
 */
public record ReleaseEntry(FullSemver version, @Nullable String changelog, String publishedIso) {

    public boolean hasChangelog() {
        return changelog != null && !changelog.isBlank();
    }
}
