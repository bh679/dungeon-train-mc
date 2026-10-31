package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.PlayerMobAdvancementEvents.EchoStrike;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link PlayerMobAdvancementEvents#mergeEchoStrike} — how a new hit on an
 * echo folds into the strike the void-push advancements (Repressed Memory / Erased) read on its
 * out-of-world death. The on-train reading is supplied as an argument, so no Minecraft bootstrap.
 */
final class EchoVoidPushStrikeTest {

    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID Q = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("first hit takes the on-train reading as-is")
    void firstHit_usesReading() {
        assertTrue(PlayerMobAdvancementEvents.mergeEchoStrike(null, P, 10L, true).onTrain());
        assertFalse(PlayerMobAdvancementEvents.mergeEchoStrike(null, P, 10L, false).onTrain());
    }

    @Test
    @DisplayName("follow-up off-train hit by the same player keeps the on-train shove")
    void followUpOffTrain_samePlayer_staysOnTrain() {
        EchoStrike shove = new EchoStrike(P, 100L, true);
        EchoStrike out = PlayerMobAdvancementEvents.mergeEchoStrike(shove, P, 140L, false);
        assertTrue(out.onTrain());
        assertEquals(140L, out.tick(), "window runs from the latest hit");
        assertEquals(P, out.playerUuid());
    }

    @Test
    @DisplayName("an on-train hit older than the window does not carry over")
    void staleOnTrainHit_doesNotCarry() {
        EchoStrike old = new EchoStrike(P, 0L, true);
        assertFalse(PlayerMobAdvancementEvents.mergeEchoStrike(old, P, 301L, false).onTrain());
        assertTrue(PlayerMobAdvancementEvents.mergeEchoStrike(old, P, 300L, false).onTrain());
    }

    @Test
    @DisplayName("another player's off-train hit takes the strike without inheriting on-train")
    void otherPlayer_doesNotInherit() {
        EchoStrike shove = new EchoStrike(P, 100L, true);
        EchoStrike out = PlayerMobAdvancementEvents.mergeEchoStrike(shove, Q, 120L, false);
        assertEquals(Q, out.playerUuid());
        assertFalse(out.onTrain());
    }

    @Test
    @DisplayName("an off-train strike followed by an on-train hit reads on-train")
    void offThenOn_readsOn() {
        EchoStrike off = new EchoStrike(P, 100L, false);
        assertTrue(PlayerMobAdvancementEvents.mergeEchoStrike(off, P, 120L, true).onTrain());
    }
}
