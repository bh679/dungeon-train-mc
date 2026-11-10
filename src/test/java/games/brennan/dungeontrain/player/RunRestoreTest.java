package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.net.relay.InventoryRestoreClient.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins when a restore's {@code run} may move a player, and that a real save's run state decodes. */
final class RunRestoreTest {

    /** Shape of a real {@code dungeontrain:player_run_state} from a 1.21.1 save (carriage 237, Normal). */
    private static final String SAVED_STATE = "{cartsBackwardSinceDeath: 36, distanceBlocks: 9474.74783126216d, "
        + "trainTimeTicks: 63830L, containersOpened: 107, uniqueChests: [[I; 20481043, 126, 20605958]], "
        + "encounteredMobs: [[I; -2133152036, -1619114189, -1995829353, -422512856]], cartsSinceDeath: 309, "
        + "weaponKills: {\"minecraft:air\": 1, \"minecraft:diamond_sword\": 52}, travelledCarriageIndex: 237, "
        + "mobKills: 53, booksReadCount: 19, damageDealt: 793.681484863162d, damageTaken: 53.56010365486145d}";

    @Test
    @DisplayName("a lone, living player in a train world's overworld can be moved")
    void allowed() {
        assertNull(RunRestore.blockedReason(1, true, true, true));
    }

    @Test
    @DisplayName("anyone else online, another dimension, death or a non-train world holds it")
    void held() {
        assertEquals("other_players_online", RunRestore.blockedReason(2, true, true, true));
        assertEquals("not_in_overworld", RunRestore.blockedReason(1, false, true, true));
        assertEquals("dead", RunRestore.blockedReason(1, true, false, true));
        assertEquals("not_a_train_world", RunRestore.blockedReason(1, true, true, false));
    }

    @Test
    @DisplayName("a saved run state decodes with its depth and stats")
    void decodesSavedState() {
        PlayerRunState state = RunRestore.decode(new Run(9140.6, SAVED_STATE, 237));
        assertNotNull(state);
        assertEquals(237, state.travelledCarriageIndex());
        assertEquals(53, state.mobKills());
        assertEquals(9474.74783126216, state.distanceBlocks(), 1e-9);
    }

    @Test
    @DisplayName("unreadable run state decodes to null rather than throwing")
    void unreadableState() {
        assertNull(RunRestore.decode(new Run(10, "{not snbt", -1)));
    }
}
