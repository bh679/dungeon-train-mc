package games.brennan.dungeontrain.portal.tribute;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomLotteryTest {

    @Test
    void nobodyToDrawIsNull() {
        assertNull(TributeRoomLottery.pick(1L, 0, List.of()));
    }

    @Test
    void sameInputsSameOwner() {
        List<String> owners = List.of("a", "b", "c", "d", "e");
        for (int pair = 0; pair < 50; pair += 3) {
            assertEquals(TributeRoomLottery.pick(42L, pair, owners), TributeRoomLottery.pick(42L, pair, owners));
        }
    }

    @Test
    void pairsSpreadAcrossOwners() {
        List<String> owners = List.of("a", "b", "c", "d", "e");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int pair = 0; pair < 600; pair += 3) seen.add(TributeRoomLottery.pick(7L, pair, owners));
        assertEquals(owners.size(), seen.size(), "every owner's room turns up somewhere on the line");
    }
}
