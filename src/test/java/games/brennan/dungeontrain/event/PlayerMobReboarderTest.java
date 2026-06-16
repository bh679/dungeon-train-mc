package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.PlayerMobAdvancementEvents.ReboarderDecision;
import games.brennan.dungeontrain.event.PlayerMobAdvancementEvents.ReboarderHit;
import games.brennan.dungeontrain.event.PlayerMobAdvancementEvents.ReboarderStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link PlayerMobAdvancementEvents#step} — the Reboarder
 * off-deck decision state machine. No Minecraft bootstrap is needed: the
 * carriage-block support test ({@code isOnCarriageDeck}) is supplied here as the
 * {@code onDeck} argument (and verified in-game), so this table pins exactly how a
 * given (support, dead, expired) observation maps to CREDIT / KEEP / DROP.
 *
 * <p>Mirrors the registry-free style of {@code TrainPassengerExemptionTest}.
 * Grace is {@code OFF_DECK_GRACE_SCANS = 2}, so an off-deck streak credits on its
 * third consecutive sample ({@code offScans} 0→1→2→CREDIT).</p>
 */
final class PlayerMobReboarderTest {

    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Strike-time hit: on the train, last seen on the deck, no off-streak yet. */
    private static ReboarderHit fresh() {
        return new ReboarderHit(P, 0L, true, true, 0);
    }

    // ---- alive + off the deck: accumulate grace, then credit ----

    @Test
    @DisplayName("alive & off-deck within grace → KEEP, off-streak grows, lastOnDeck clears")
    void offDeck_withinGrace_keeps() {
        ReboarderStep out = PlayerMobAdvancementEvents.step(fresh(), false, false, false);
        assertEquals(ReboarderDecision.KEEP, out.decision());
        assertEquals(1, out.next().offScans());
        assertFalse(out.next().lastOnDeck());
    }

    @Test
    @DisplayName("alive & off-deck past grace → CREDIT (the reporter's case)")
    void offDeck_pastGrace_credits() {
        // offScans at the grace bound (2); the next off sample makes 3 (> 2).
        ReboarderHit atGrace = new ReboarderHit(P, 0L, true, false, 2);
        ReboarderStep out = PlayerMobAdvancementEvents.step(atGrace, false, false, false);
        assertEquals(ReboarderDecision.CREDIT, out.decision());
    }

    // ---- alive + on the deck ----

    @Test
    @DisplayName("alive & on-deck → KEEP, off-streak resets, lastOnDeck set")
    void onDeck_resets() {
        ReboarderHit offByOne = new ReboarderHit(P, 0L, true, false, 1);
        ReboarderStep out = PlayerMobAdvancementEvents.step(offByOne, true, false, false);
        assertEquals(ReboarderDecision.KEEP, out.decision());
        assertEquals(0, out.next().offScans());
        assertTrue(out.next().lastOnDeck());
    }

    @Test
    @DisplayName("alive & on-deck but window expired → DROP (never left in time)")
    void onDeck_expired_drops() {
        ReboarderStep out = PlayerMobAdvancementEvents.step(fresh(), true, false, true);
        assertEquals(ReboarderDecision.DROP, out.decision());
    }

    // ---- not on the train when struck: never credited ----

    @Test
    @DisplayName("off-deck but was not on the train at strike → KEEP, never credits")
    void notOnTrain_neverCredits() {
        ReboarderHit notAboard = new ReboarderHit(P, 0L, false, false, 5);
        ReboarderStep out = PlayerMobAdvancementEvents.step(notAboard, false, false, false);
        assertEquals(ReboarderDecision.KEEP, out.decision());
    }

    // ---- unloaded / dead ----

    @Test
    @DisplayName("unloaded after last seen off-deck → CREDIT (fell off, then chunk unloaded)")
    void unloaded_lastOffDeck_credits() {
        ReboarderHit lastOff = new ReboarderHit(P, 0L, true, false, 1);
        ReboarderStep out = PlayerMobAdvancementEvents.step(lastOff, null, false, false);
        assertEquals(ReboarderDecision.CREDIT, out.decision());
    }

    @Test
    @DisplayName("dead while last seen on-deck → DROP (killed on the deck, not pushed off)")
    void dead_lastOnDeck_drops() {
        ReboarderStep out = PlayerMobAdvancementEvents.step(fresh(), null, true, false);
        assertEquals(ReboarderDecision.DROP, out.decision());
    }

    @Test
    @DisplayName("unloaded while last seen on-deck, window open → KEEP (wait for reload)")
    void unloaded_lastOnDeck_open_keeps() {
        ReboarderStep out = PlayerMobAdvancementEvents.step(fresh(), null, false, false);
        assertEquals(ReboarderDecision.KEEP, out.decision());
    }

    @Test
    @DisplayName("unloaded while last seen on-deck, window expired → DROP")
    void unloaded_lastOnDeck_expired_drops() {
        ReboarderStep out = PlayerMobAdvancementEvents.step(fresh(), null, false, true);
        assertEquals(ReboarderDecision.DROP, out.decision());
    }
}
