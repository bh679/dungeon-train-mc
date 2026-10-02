package games.brennan.dungeontrain.compat;

import games.brennan.discordpresence.client.VersionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Discord Presence jar DT bundles must be a release build. DP draws its top-left version HUD —
 * on the title screen and in-game — whenever the branch baked into its jar is anything but
 * {@code main}, so a pinned jar that baked a feature branch or a detached-HEAD SHA (DP 0.60.0 did)
 * shows that HUD to every player.
 *
 * <p>Reads DP's own {@link VersionInfo} — the value its HUD gates on — rather than the properties
 * file, which the test's module cannot see. Fails on purpose against an unreleased DP feature-branch
 * jar swapped in locally; run those sessions with {@code -x test}.</p>
 */
class BundledDiscordPresenceReleaseTest {

    @Test
    void bundledJarBakesTheReleaseBranch() {
        assertEquals("main", VersionInfo.BRANCH,
            "Discord Presence " + VersionInfo.VERSION + " is a dev build — its version HUD would"
                + " draw for every player. Pin a jar released by DP's release.yml.");
    }
}
