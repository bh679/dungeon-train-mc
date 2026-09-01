package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How full a player's relay profile is, and when one more build would cost them their oldest.
 *
 * <p>Worth pinning because being wrong is silent in both directions: count too few and the mod lets
 * an upload through that deletes a build, count too many and it refuses uploads for a profile that
 * had room.</p>
 */
final class BuilderProfileCapTest {

    @Test
    @DisplayName("only profile builds count — a published one is not occupying a slot")
    void countsOnlyProfileBuilds() {
        List<SharedCarriageClient.ProfileBuild> builds = new ArrayList<>();
        builds.add(build("profile"));
        builds.add(build("published"));
        builds.add(build("profile"));
        // The relay's own eviction filters on visibility='profile', so anything else is out of scope.
        builds.add(build(null));

        assertEquals(2, BuilderProfileCap.used(builds));
    }

    @Test
    @DisplayName("an unreadable listing counts as nothing rather than as full")
    void nullListingIsNotFull() {
        // A failed relay call must never read as "your profile is full" — that would block saves.
        assertEquals(0, BuilderProfileCap.used(null));
        assertFalse(BuilderProfileCap.isFull(0));
    }

    @Test
    @DisplayName("the profile is full only once every slot is taken")
    void fullAtTheCap() {
        assertFalse(BuilderProfileCap.isFull(BuilderProfileCap.MAX_PROFILE_BUILDS - 1));
        assertTrue(BuilderProfileCap.isFull(BuilderProfileCap.MAX_PROFILE_BUILDS));
        // Over the cap is possible: an operator can lower it, or older builds predate this guard.
        assertTrue(BuilderProfileCap.isFull(BuilderProfileCap.MAX_PROFILE_BUILDS + 5));
    }

    @Test
    @DisplayName("the allowance is what is left, and never negative")
    void remainingIsNeverNegative() {
        assertEquals(BuilderProfileCap.MAX_PROFILE_BUILDS, BuilderProfileCap.remaining(0));
        assertEquals(1, BuilderProfileCap.remaining(BuilderProfileCap.MAX_PROFILE_BUILDS - 1));
        assertEquals(0, BuilderProfileCap.remaining(BuilderProfileCap.MAX_PROFILE_BUILDS));
        // A negative allowance would become a negative sublist bound in the restore queue.
        assertEquals(0, BuilderProfileCap.remaining(BuilderProfileCap.MAX_PROFILE_BUILDS + 40));
    }

    private static SharedCarriageClient.ProfileBuild build(String visibility) {
        return new SharedCarriageClient.ProfileBuild(1, "carriage", "", "name", visibility, "builder",
                "", "approved", "none", 9, 7, 7, 0, 0L, false, "uuid", "owner");
    }
}
