package games.brennan.dungeontrain.portal.tribute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static games.brennan.dungeontrain.portal.tribute.TributeRoomDeathKit.NO_SLOT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Which slot each tribute-room kit stack goes back into. Stacks are "item:count"; "" is an empty slot. */
class TributeRoomDeathKitTest {

    private static final BiPredicate<String, String> EXACT = String::equals;
    private static final BiPredicate<String, String> LOOSE = (a, b) -> item(a).equals(item(b));
    private static final Predicate<String> EMPTY = String::isEmpty;

    private static String item(String s) {
        int c = s.indexOf(':');
        return c < 0 ? s : s.substring(0, c);
    }

    private static int[] assign(List<String> kit, List<String> layout) {
        return TributeRoomDeathKit.assignSlots(kit, layout, EXACT, LOOSE, EMPTY);
    }

    @Test
    @DisplayName("Armour, offhand and hotbar stacks go back to the slots they were carried in")
    void eachStackFindsItsSlot() {
        // slot 0 sword, 1 empty, 2 bread, 3 helmet (armour), 4 shield (offhand)
        List<String> layout = List.of("sword:1", "", "bread:12", "helmet:1", "shield:1");
        List<String> kit = List.of("shield:1", "helmet:1", "bread:12", "sword:1");
        assertArrayEquals(new int[] {4, 3, 2, 0}, assign(kit, layout));
    }

    @Test
    @DisplayName("Identical stacks each get their own slot, never the same one twice")
    void duplicatesSpreadOut() {
        List<String> layout = List.of("arrow:64", "dirt:1", "arrow:64");
        assertArrayEquals(new int[] {0, 2, NO_SLOT}, assign(List.of("arrow:64", "arrow:64", "arrow:64"), layout));
    }

    @Test
    @DisplayName("A count changed on the way to the drops still finds its slot, after exact matches are placed")
    void looseMatchAfterExact() {
        List<String> layout = List.of("bread:5", "bread:12");
        // bread:12 is exact for slot 1; bread:3 then falls back to the only bread slot left
        assertArrayEquals(new int[] {0, 1}, assign(List.of("bread:3", "bread:12"), layout));
    }

    @Test
    @DisplayName("A drop that was never carried, or with no layout at all, gets no slot")
    void unmatchedGetsNoSlot() {
        assertArrayEquals(new int[] {NO_SLOT}, assign(List.of("bone:2"), List.of("sword:1", "")));
        assertArrayEquals(new int[] {NO_SLOT, NO_SLOT}, assign(List.of("sword:1", "bread:1"), List.of()));
    }

    @Test
    @DisplayName("An empty layout slot is never matched")
    void emptySlotsSkipped() {
        assertArrayEquals(new int[] {NO_SLOT}, assign(List.of(""), List.of("")));
    }
}
