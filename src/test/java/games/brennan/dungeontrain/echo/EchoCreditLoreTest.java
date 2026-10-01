package games.brennan.dungeontrain.echo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where an echo's credit goes: appended under four lines, otherwise replacing one. */
final class EchoCreditLoreTest {

    @Test
    @DisplayName("appends when the item has fewer than four lines")
    void appendsUnderCap() {
        assertEquals(EchoCreditLore.APPEND, EchoCreditLore.slotFor(List.of(), List.of()));
        assertEquals(EchoCreditLore.APPEND, EchoCreditLore.slotFor(List.of("a", "b", "c"), List.of()));
    }

    @Test
    @DisplayName("at four lines with no earlier credit, replaces the last line")
    void replacesLastLine() {
        assertEquals(3, EchoCreditLore.slotFor(List.of("a", "b", "c", "d"), List.of()));
    }

    @Test
    @DisplayName("at four lines, replaces the oldest echo credit still on the item")
    void replacesOldestCredit() {
        List<String> lines = List.of("Crafted by Alex", "Carried by Steve", "Given by Sam", "lore");
        assertEquals(1, EchoCreditLore.slotFor(lines, List.of("Carried by Steve", "Given by Sam")));
    }

    @Test
    @DisplayName("skips recorded credits that were already overwritten")
    void skipsGoneCredits() {
        List<String> lines = List.of("a", "b", "Given by Sam", "d");
        assertEquals(2, EchoCreditLore.slotFor(lines, List.of("Carried by Steve", "Given by Sam")));
    }

    @Test
    @DisplayName("an item already over four lines still replaces rather than grows")
    void overCapReplaces() {
        assertEquals(5, EchoCreditLore.slotFor(List.of("a", "b", "c", "d", "e", "f"), List.of()));
    }
}
