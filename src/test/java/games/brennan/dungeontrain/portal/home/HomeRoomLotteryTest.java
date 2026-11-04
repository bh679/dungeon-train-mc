package games.brennan.dungeontrain.portal.home;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HomeRoomLotteryTest {

    @Test
    void nobodyToDrawIsNull() {
        assertNull(HomeRoomLottery.pick(1L, 0, List.of()));
    }

    @Test
    void sameInputsSameOwner() {
        List<String> owners = List.of("a", "b", "c", "d", "e");
        for (int pair = 0; pair < 50; pair += 3) {
            assertEquals(HomeRoomLottery.pick(42L, pair, owners), HomeRoomLottery.pick(42L, pair, owners));
        }
    }

    @Test
    void pairsSpreadAcrossOwners() {
        List<String> owners = List.of("a", "b", "c", "d", "e");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int pair = 0; pair < 600; pair += 3) seen.add(HomeRoomLottery.pick(7L, pair, owners));
        assertEquals(owners.size(), seen.size(), "every owner's room turns up somewhere on the line");
    }
}
