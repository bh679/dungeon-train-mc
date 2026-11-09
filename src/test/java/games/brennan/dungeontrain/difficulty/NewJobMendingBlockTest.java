package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may sell Mending: only merchants the train spawned with a job. No NeoForge bootstrap. */
final class NewJobMendingBlockTest {

    private static final String ROLLED = "dungeontrain_trades_rerolled";

    @Test
    @DisplayName("a train-spawned merchant keeps Mending, old saves included")
    void trainSpawnedAllowed() {
        assertTrue(NewJobMendingBlock.mayOfferMending(true, Set.of(ROLLED), ROLLED));
    }

    @Test
    @DisplayName("a villager that took its job in play, on or off the train, can't sell Mending")
    void forcedJobsBlocked() {
        assertFalse(NewJobMendingBlock.mayOfferMending(false, Set.of(), ROLLED));
        assertFalse(NewJobMendingBlock.mayOfferMending(true, Set.of(), ROLLED));
        assertFalse(NewJobMendingBlock.mayOfferMending(true, Set.of(ROLLED, NewJobMendingBlock.BLOCKED_TAG), ROLLED));
        assertFalse(NewJobMendingBlock.mayOfferMending(true, null, ROLLED));
    }
}
