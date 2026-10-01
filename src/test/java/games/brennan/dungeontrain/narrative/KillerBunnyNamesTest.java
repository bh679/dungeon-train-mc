package games.brennan.dungeontrain.narrative;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pool-shape tests for {@link KillerBunnyNames}. */
final class KillerBunnyNamesTest {

    @Test
    @DisplayName("pool is non-empty with no blank or duplicate names")
    void poolShape() {
        List<String> names = KillerBunnyNames.names();
        assertFalse(names.isEmpty());
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            assertFalse(n.isBlank(), "blank entry");
            assertEquals(n, n.strip(), "untrimmed entry: '" + n + "'");
            assertTrue(seen.add(n), "duplicate entry: " + n);
        }
    }

    @Test
    @DisplayName("the headline name is in the pool")
    void headlineName() {
        assertTrue(KillerBunnyNames.names().contains("Rabbit of Caerbannog"));
    }

    @Test
    @DisplayName("pick always returns a pool member")
    void pickReturnsPoolMember() {
        RandomSource rng = RandomSource.create(42L);
        for (int i = 0; i < 500; i++) {
            assertTrue(KillerBunnyNames.names().contains(KillerBunnyNames.pick(rng)));
        }
    }
}
