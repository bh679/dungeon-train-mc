package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may sell Mending: only villagers carrying the train's spawn marker. No NeoForge bootstrap. */
final class NewJobMendingBlockTest {

    @Test
    @DisplayName("only the spawn marker allows Mending")
    void onlySpawnMarkerAllowsMending() {
        assertTrue(NewJobMendingBlock.mayOfferMending(Set.of(NewJobMendingBlock.MENDING_OK_TAG, "x")));
        assertFalse(NewJobMendingBlock.mayOfferMending(Set.of("dungeontrain_trades_rerolled")));
        assertFalse(NewJobMendingBlock.mayOfferMending(List.of()));
        assertFalse(NewJobMendingBlock.mayOfferMending((Set<String>) null));
    }
}
