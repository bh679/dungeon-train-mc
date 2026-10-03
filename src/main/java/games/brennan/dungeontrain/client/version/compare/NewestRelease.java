package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.bugresponse.ReleasesBehind;

import java.util.Optional;

/**
 * The newest real release on <em>any</em> launcher, and which launcher has it — what the title-screen
 * Versions prompt and the death screen's Update button offer. Asking only the player's own launcher
 * hides a release whenever that launcher lags: CurseForge only lists a build once its review clears,
 * so a CurseForge player could sit "up to date" for weeks while Modrinth was releases ahead.
 *
 * <p>"Real release" is {@link ReleasesBehind}'s rule — a newer MAJOR.MINOR — so the auto-release
 * cascade's PATCH ticks never count. On a tie the player's own launcher wins, so nobody is sent to
 * another launcher for a build their own already has. Pure, so it is unit-tested.</p>
 */
public final class NewestRelease {

    /** The version to update to, the launcher listing it, and how many real releases behind that is. */
    public record Target(Platform platform, FullSemver version, int releasesBehind) {}

    private NewestRelease() {}

    /**
     * @param launcher the launcher the player runs under; {@code own} is its listing
     * @param own      the player's launcher's listing, when it has arrived
     * @param other    the other launcher's listing, when it has arrived
     * @return the newest newer real release across both, empty when up to date on everything loaded
     */
    public static Optional<Target> across(FullSemver installed, Platform launcher,
                                          Optional<PlatformVersions> own, Optional<PlatformVersions> other) {
        Optional<Target> mine = own.flatMap(l -> target(installed, launcher, l));
        Optional<Target> theirs = other.flatMap(l -> target(installed, launcher.other(), l));
        if (mine.isEmpty()) return theirs;
        if (theirs.isEmpty()) return mine;
        return theirs.get().version().isNewerThan(mine.get().version()) ? theirs : mine;
    }

    private static Optional<Target> target(FullSemver installed, Platform platform, PlatformVersions listing) {
        return ReleasesBehind.updateTarget(listing, installed)
                .map(v -> new Target(platform, v, ReleasesBehind.count(listing, installed)));
    }
}
