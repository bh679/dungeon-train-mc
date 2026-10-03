package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewestReleaseTest {

    private static FullSemver v(String s) {
        return FullSemver.parse(s).orElseThrow();
    }

    private static Optional<PlatformVersions> listing(Platform p, String... versions) {
        return Optional.of(new PlatformVersions(p, Arrays.stream(versions)
                .map(s -> new ReleaseEntry(v(s), null, ""))
                .toList()));
    }

    @Test
    @DisplayName("CurseForge stuck on the installed build, Modrinth ahead: Modrinth's release is the target")
    void otherLauncherAhead() {
        NewestRelease.Target t = NewestRelease.across(v("0.927.0"), Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.925.0", "0.927.0"),
                listing(Platform.MODRINTH, "0.927.0", "0.1093.0", "0.1104.0")).orElseThrow();
        assertEquals(Platform.MODRINTH, t.platform());
        assertEquals(v("0.1104.0"), t.version());
        assertEquals(2, t.releasesBehind());
    }

    @Test
    @DisplayName("own launcher ahead of the other: own launcher's release")
    void ownAhead() {
        NewestRelease.Target t = NewestRelease.across(v("0.900.0"), Platform.MODRINTH,
                listing(Platform.MODRINTH, "0.950.0"),
                listing(Platform.CURSEFORGE, "0.927.0")).orElseThrow();
        assertEquals(Platform.MODRINTH, t.platform());
        assertEquals(v("0.950.0"), t.version());
    }

    @Test
    @DisplayName("both launchers on the same newest release: the player's own launcher wins")
    void tieGoesToOwn() {
        NewestRelease.Target t = NewestRelease.across(v("0.900.0"), Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.950.0"),
                listing(Platform.MODRINTH, "0.950.0")).orElseThrow();
        assertEquals(Platform.CURSEFORGE, t.platform());
    }

    @Test
    @DisplayName("a cascade PATCH tick is not a release to update to")
    void patchIgnored() {
        assertTrue(NewestRelease.across(v("0.1104.0"), Platform.MODRINTH,
                listing(Platform.MODRINTH, "0.1104.3"),
                listing(Platform.CURSEFORGE, "0.1104.0")).isEmpty());
    }

    @Test
    @DisplayName("only one listing has arrived: it still answers")
    void oneListingMissing() {
        NewestRelease.Target t = NewestRelease.across(v("0.927.0"), Platform.CURSEFORGE,
                Optional.empty(), listing(Platform.MODRINTH, "0.1104.0")).orElseThrow();
        assertEquals(Platform.MODRINTH, t.platform());
        assertTrue(NewestRelease.across(v("0.927.0"), Platform.CURSEFORGE,
                listing(Platform.CURSEFORGE, "0.927.0"), Optional.empty()).isEmpty());
    }

    @Test
    @DisplayName("no listings, or a dev build ahead of both: nothing to offer")
    void nothing() {
        assertTrue(NewestRelease.across(v("0.927.0"), Platform.MODRINTH,
                Optional.empty(), Optional.empty()).isEmpty());
        assertTrue(NewestRelease.across(v("0.1200.0"), Platform.MODRINTH,
                listing(Platform.MODRINTH, "0.1104.0"),
                listing(Platform.CURSEFORGE, "0.927.0")).isEmpty());
    }
}
