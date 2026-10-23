package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where restored stacks land and what has to move aside — with strings standing in for stacks
 * ("" is an empty slot). The rule under test: nothing the player holds is ever lost.
 */
final class RestorePlacementTest {

    private static RestorePlacement.Plan<String> plan(Map<Integer, String> inventory,
                                                      List<RestorePlacement.Target<String>> targets) {
        return RestorePlacement.plan(targets, i -> inventory.getOrDefault(i, ""), String::isEmpty);
    }

    @Test
    @DisplayName("snapshot slots map onto inventory indices: main, armour, offhand; anything else is unknown")
    void slotMapping() {
        assertEquals(0, RestorePlacement.toInventoryIndex(0));
        assertEquals(35, RestorePlacement.toInventoryIndex(35));
        assertEquals(36, RestorePlacement.toInventoryIndex(100));
        assertEquals(39, RestorePlacement.toInventoryIndex(103));
        assertEquals(40, RestorePlacement.toInventoryIndex(-106));
        assertEquals(-1, RestorePlacement.toInventoryIndex(36));
        assertEquals(-1, RestorePlacement.toInventoryIndex(104));
        assertEquals(-1, RestorePlacement.toInventoryIndex(-1));
    }

    @Test
    @DisplayName("an empty slot just takes the restored stack")
    void emptySlot() {
        RestorePlacement.Plan<String> p = plan(Map.of(),
            List.of(new RestorePlacement.Target<>(0, "sword")));
        assertEquals(Map.of(0, "sword"), p.placements());
        assertTrue(p.displaced().isEmpty());
        assertEquals(0, p.skipped());
    }

    @Test
    @DisplayName("an occupied slot or worn armour piece is displaced, never overwritten silently")
    void occupiedSlotsDisplaced() {
        Map<Integer, String> inv = new HashMap<>(Map.of(0, "starting_book", 39, "leather_cap", 5, "bread"));
        RestorePlacement.Plan<String> p = plan(inv, List.of(
            new RestorePlacement.Target<>(0, "sword"),
            new RestorePlacement.Target<>(103, "netherite_helmet")));
        assertEquals("sword", p.placements().get(0));
        assertEquals("netherite_helmet", p.placements().get(39));
        assertEquals(List.of("starting_book", "leather_cap"), p.displaced());
        // Slot 5 is not in the snapshot, so the player's bread stays where it is.
        assertTrue(!p.placements().containsKey(5));
    }

    @Test
    @DisplayName("an unknown slot or a slot claimed twice is skipped and counted")
    void skipped() {
        RestorePlacement.Plan<String> p = plan(Map.of(), List.of(
            new RestorePlacement.Target<>(7, "first"),
            new RestorePlacement.Target<>(7, "second"),
            new RestorePlacement.Target<>(250, "nowhere")));
        assertEquals(Map.of(7, "first"), p.placements());
        assertEquals(2, p.skipped());
    }

    @Test
    @DisplayName("displaced stacks pack 27 to a chest, spilling into a second chest in order")
    void packIntoChests() {
        List<String> stacks = new ArrayList<>();
        IntStream.range(0, 30).forEach(i -> stacks.add("s" + i));
        List<List<String>> chests = RestorePlacement.packIntoChests(stacks);
        assertEquals(2, chests.size());
        assertEquals(27, chests.get(0).size());
        assertEquals(List.of("s27", "s28", "s29"), chests.get(1));
        assertTrue(RestorePlacement.packIntoChests(List.of()).isEmpty());
    }
}
