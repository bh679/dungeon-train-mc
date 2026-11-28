package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The seed group's numbering: base 0 is the old numbering, a nap's base shifts it by whole groups. */
final class TrainSeedNumberingTest {

    @Test
    @DisplayName("base 0 numbers the seed exactly as before")
    void baseZeroUnchanged() {
        TrainAssembler.SeedNumbering s = TrainAssembler.seedNumbering(0, 0, 3);
        assertEquals(0, s.initialPIdx());
        assertEquals(0, s.seedAnchor());
        assertEquals(0, s.localGroupIdx());
        TrainAssembler.SeedNumbering back = TrainAssembler.seedNumbering(-2, 0, 3);
        assertEquals(-2, back.initialPIdx());
        assertEquals(-3, back.seedAnchor());
        assertEquals(-1, back.localGroupIdx());
    }

    @Test
    @DisplayName("a nap's base moves only the numbers, by whole groups; placement stays local")
    void baseShiftsNumbering() {
        TrainAssembler.SeedNumbering s = TrainAssembler.seedNumbering(0, 957, 3);
        assertEquals(957, s.initialPIdx());
        assertEquals(957, s.seedAnchor());
        assertEquals(0, s.localGroupIdx(), "laid out where base 0 would put it");
        TrainAssembler.SeedNumbering odd = TrainAssembler.seedNumbering(0, 251, 3);
        assertEquals(249, odd.seedAnchor(), "rounded down to a group so anchors stay group multiples");
        assertEquals(0, Math.floorMod(odd.seedAnchor(), 3));
        TrainAssembler.SeedNumbering behind = TrainAssembler.seedNumbering(0, -40, 3);
        assertEquals(-42, behind.seedAnchor(), "behind spawn rounds toward -infinity like every anchor");
    }
}
