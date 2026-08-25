package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one reload per held episode.
 *
 * <p>{@code reloadFromHolding} is not a poll: a held sub-level stays held for the whole surfacing
 * window, so a call per tick re-triggers Sable's "wasn't present in the holding chunk" ERROR twenty
 * times a second while the first reload is still working. The throttle has to allow exactly one
 * issue per episode and then re-arm — never permanently, or a pair culled a second time would have
 * no way back and the room around a player would be sealed for good.</p>
 */
class PortalCarriageRevivalTest {

    private static final int PAIR = 24;
    private static final int OTHER_PAIR = 48;

    private static final UUID SUB_LEVEL = UUID.nameUUIDFromBytes("group-a".getBytes());
    private static final UUID RESPAWNED = UUID.nameUUIDFromBytes("group-b".getBytes());

    @BeforeEach
    @AfterEach
    void reset() {
        PortalCarriageRevival.clear();
    }

    @Test
    @DisplayName("the first ask issues a reload and the rest of the episode does not")
    void issuesOncePerEpisode() {
        assertTrue(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
        assertFalse(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
        assertFalse(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
    }

    @Test
    @DisplayName("a different sub-level at the same pair is a new episode")
    void newSubLevelRearms() {
        assertTrue(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
        // The pair key is a fixed place along the track, so it outlives the carriage occupying it:
        // the train can roll on and a freshly spawned group take the same index. That group has its
        // own sub-level and is owed its own reload.
        assertTrue(PortalCarriageRevival.claimReload(PAIR, RESPAWNED));
        assertFalse(PortalCarriageRevival.claimReload(PAIR, RESPAWNED));
    }

    @Test
    @DisplayName("two pairs holding the same episode do not silence each other")
    void throttleIsPerPair() {
        assertTrue(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
        assertTrue(PortalCarriageRevival.claimReload(OTHER_PAIR, SUB_LEVEL));
    }

    @Test
    @DisplayName("forgetting a pair re-arms it — a second cull is a second episode")
    void forgetRearms() {
        assertTrue(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
        PortalCarriageRevival.forget(PAIR);
        assertTrue(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
    }

    @Test
    @DisplayName("a handle with no sub-level id claims nothing rather than claiming everything")
    void nullSubLevelNeverClaims() {
        assertFalse(PortalCarriageRevival.claimReload(PAIR, null));
        // And it must not have consumed the episode: the real id still gets its issue.
        assertTrue(PortalCarriageRevival.claimReload(PAIR, SUB_LEVEL));
    }
}
