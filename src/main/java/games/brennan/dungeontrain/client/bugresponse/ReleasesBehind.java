package games.brennan.dungeontrain.client.bugresponse;

import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.PlatformVersions;
import games.brennan.dungeontrain.client.version.compare.ReleaseEntry;

import java.util.Optional;

/**
 * How far behind a launcher's listing the installed build is, counted in real releases only: a newer
 * MAJOR.MINOR. The auto-release cascade's PATCH ticks ({@code 0.1078.1}, {@code .2}, …) are ignored,
 * the same rule as the title-screen update badge, so a Modrinth player is not told to update after
 * every tick.
 */
public final class ReleasesBehind {

    private ReleasesBehind() {}

    /** Distinct newer MAJOR.MINOR releases in {@code listing}; 0 when up to date or ahead. */
    public static int count(PlatformVersions listing, FullSemver installed) {
        return (int) listing.entries().stream()
                .map(ReleaseEntry::version)
                .filter(v -> isNewerRelease(v, installed))
                .map(v -> v.major() + "." + v.minor())
                .distinct()
                .count();
    }

    /** The newest version on {@code listing} when it is a newer real release than {@code installed}. */
    public static Optional<FullSemver> updateTarget(PlatformVersions listing, FullSemver installed) {
        return listing.latest()
                .map(ReleaseEntry::version)
                .filter(v -> isNewerRelease(v, installed));
    }

    static boolean isNewerRelease(FullSemver candidate, FullSemver installed) {
        if (candidate.major() != installed.major()) return candidate.major() > installed.major();
        return candidate.minor() > installed.minor();
    }
}
