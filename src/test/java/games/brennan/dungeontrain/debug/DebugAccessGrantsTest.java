package games.brennan.dungeontrain.debug;

import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DebugAccessGrants} decides who can open the F3+4 debug panel, so the tests that matter are
 * the ones pinning it <em>closed</em>: an empty store denies, a lapsed grant denies, and a save
 * that sat on disk past its grants comes back denying.
 */
final class DebugAccessGrantsTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("an empty store denies everyone — there is no default access")
    void empty_deniesEveryone() {
        DebugAccessGrants grants = new DebugAccessGrants();
        assertFalse(grants.isGranted(ALICE));
        assertNull(grants.grantFor(ALICE));
        assertTrue(grants.isEmpty());
    }

    @Test
    @DisplayName("a live grant permits, and only the player it names")
    void liveGrant_permitsOnlyItsOwner() {
        DebugAccessGrants grants = new DebugAccessGrants();
        grants.apply(ALICE, futureGrant(10));

        assertTrue(grants.isGranted(ALICE));
        assertFalse(grants.isGranted(BOB));
    }

    @Test
    @DisplayName("a grant whose expiry has passed denies without waiting for a sweep")
    void lapsedGrant_deniesImmediately() {
        DebugAccessGrants grants = new DebugAccessGrants();
        grants.apply(ALICE, new DebugAccessGrants.Grant(System.currentTimeMillis() - MINUTE, "relay"));

        assertFalse(grants.isGranted(ALICE));
        assertNull(grants.grantFor(ALICE));
    }

    @Test
    @DisplayName("expiry 0 means forever")
    void foreverGrant_neverLapses() {
        DebugAccessGrants grants = new DebugAccessGrants();
        grants.apply(ALICE, new DebugAccessGrants.Grant(DebugAccessGrants.NEVER_EXPIRES, "admin-page"));

        assertTrue(grants.isGranted(ALICE));
        assertTrue(grants.sweepExpired().isEmpty());
        assertTrue(grants.isGranted(ALICE));
    }

    @Test
    @DisplayName("a null grant revokes a cached one")
    void nullGrant_revokes() {
        DebugAccessGrants grants = new DebugAccessGrants();
        grants.apply(ALICE, futureGrant(10));

        assertTrue(grants.apply(ALICE, null), "revoking a held grant is a change");
        assertFalse(grants.isGranted(ALICE));
        assertFalse(grants.apply(ALICE, null), "revoking again changes nothing");
    }

    @Test
    @DisplayName("apply reports whether access actually changed, so unchanged polls don't re-sync")
    void apply_reportsChangeOnly() {
        DebugAccessGrants grants = new DebugAccessGrants();
        DebugAccessGrants.Grant grant = futureGrant(10);

        assertTrue(grants.apply(ALICE, grant), "first grant is a change");
        assertFalse(grants.apply(ALICE, grant), "an identical poll result is not");
        assertTrue(grants.apply(ALICE, futureGrant(20)), "an extended expiry is");
    }

    @Test
    @DisplayName("sweep reports exactly the players whose grant lapsed")
    void sweep_reportsLapsedPlayers() {
        DebugAccessGrants grants = new DebugAccessGrants();
        grants.apply(ALICE, new DebugAccessGrants.Grant(System.currentTimeMillis() - MINUTE, "relay"));
        grants.apply(BOB, futureGrant(10));

        List<UUID> lapsed = grants.sweepExpired();

        assertEquals(List.of(ALICE), lapsed);
        assertTrue(grants.isGranted(BOB));
    }

    @Test
    @DisplayName("NBT round-trip keeps a live grant, expiry and source intact")
    void nbtRoundTrip_keepsLiveGrant() {
        DebugAccessGrants saved = new DebugAccessGrants();
        DebugAccessGrants.Grant grant = futureGrant(30);
        saved.apply(ALICE, grant);

        DebugAccessGrants loaded = new DebugAccessGrants();
        loaded.loadFrom(saved.toTag());

        DebugAccessGrants.Grant restored = loaded.grantFor(ALICE);
        assertNotNull(restored);
        assertEquals(grant.expiresAtMs(), restored.expiresAtMs());
        assertEquals(grant.source(), restored.source());
    }

    @Test
    @DisplayName("a save that outlived its grants loads back granting nothing")
    void nbtRoundTrip_dropsLapsedGrants() {
        DebugAccessGrants saved = new DebugAccessGrants();
        saved.apply(ALICE, new DebugAccessGrants.Grant(System.currentTimeMillis() - MINUTE, "relay"));
        saved.apply(BOB, futureGrant(10));
        ListTag tag = saved.toTag();

        DebugAccessGrants loaded = new DebugAccessGrants();
        loaded.loadFrom(tag);

        assertFalse(loaded.isGranted(ALICE));
        assertTrue(loaded.isGranted(BOB));
    }

    @Test
    @DisplayName("absent saved data (null list) loads as an empty, denying store")
    void loadFrom_nullIsEmpty() {
        DebugAccessGrants grants = new DebugAccessGrants();
        grants.apply(ALICE, futureGrant(10));

        grants.loadFrom(null);

        assertTrue(grants.isEmpty());
        assertFalse(grants.isGranted(ALICE));
    }

    @Test
    @DisplayName("every duration block resolves to its own expiry, and forever to 0")
    void durations_resolveToExpectedExpiries() {
        long now = 1_000_000L;

        assertEquals(now + 5L * MINUTE, DebugAccessGrants.Duration.FIVE_MINUTES.expiryFrom(now));
        assertEquals(now + 20L * MINUTE, DebugAccessGrants.Duration.TWENTY_MINUTES.expiryFrom(now));
        assertEquals(now + 60L * MINUTE, DebugAccessGrants.Duration.ONE_HOUR.expiryFrom(now));
        assertEquals(now + 24L * 60L * MINUTE, DebugAccessGrants.Duration.ONE_DAY.expiryFrom(now));
        assertEquals(now + 7L * 24L * 60L * MINUTE, DebugAccessGrants.Duration.ONE_WEEK.expiryFrom(now));
        assertEquals(now + 30L * 24L * 60L * MINUTE, DebugAccessGrants.Duration.ONE_MONTH.expiryFrom(now));
        assertEquals(DebugAccessGrants.NEVER_EXPIRES, DebugAccessGrants.Duration.FOREVER.expiryFrom(now));
    }

    @Test
    @DisplayName("duration tokens parse case-insensitively; anything else is null")
    void durationTokens_parse() {
        assertEquals(DebugAccessGrants.Duration.FIVE_MINUTES, DebugAccessGrants.Duration.fromToken("5m"));
        assertEquals(DebugAccessGrants.Duration.ONE_MONTH, DebugAccessGrants.Duration.fromToken(" 1MO "));
        assertEquals(DebugAccessGrants.Duration.FOREVER, DebugAccessGrants.Duration.fromToken("forever"));
        assertNull(DebugAccessGrants.Duration.fromToken("2w"));
        assertNull(DebugAccessGrants.Duration.fromToken(null));
    }

    private static DebugAccessGrants.Grant futureGrant(long minutesFromNow) {
        return new DebugAccessGrants.Grant(System.currentTimeMillis() + minutesFromNow * MINUTE, "relay");
    }
}
