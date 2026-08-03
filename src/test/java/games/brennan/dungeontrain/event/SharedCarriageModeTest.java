package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which pool a world belongs to. Exercised through the generic seam with named stand-ins for players
 * — the rule is about the shape of the player list, not about Minecraft.
 */
class SharedCarriageModeTest {

    /** "cheated" = the player's name is in this set. */
    private static Predicate<String> cheating(String... names) {
        Set<String> set = Set.of(names);
        return set::contains;
    }

    @Test
    void anEmptyOrPlayerlessWorldIsNormal() {
        assertEquals(SharedCarriageMode.NORMAL, SharedCarriageMode.of(List.of(), cheating("anyone")));
        assertEquals(SharedCarriageMode.NORMAL, SharedCarriageMode.of(null, cheating("anyone")));
        assertEquals(SharedCarriageMode.NORMAL, SharedCarriageMode.current(null));
    }

    @Test
    void noCheatingPlayerMeansTheNormalPool() {
        assertEquals(SharedCarriageMode.NORMAL,
                SharedCarriageMode.of(List.of("alice", "bob"), cheating("mallory")));
    }

    @Test
    void anyCheatingPlayerTaintsTheWholeWorld() {
        // ANY, not the host: one Free Play player anywhere in the world moves it to that pool, which is
        // what stops them ever editing a normal-pool build.
        assertEquals(SharedCarriageMode.FREE_PLAY,
                SharedCarriageMode.of(List.of("alice", "mallory"), cheating("mallory")));
        assertEquals(SharedCarriageMode.FREE_PLAY,
                SharedCarriageMode.of(List.of("mallory", "alice"), cheating("mallory")),
                "position in the list is irrelevant");
        assertEquals(SharedCarriageMode.FREE_PLAY,
                SharedCarriageMode.of(List.of("mallory"), cheating("mallory")),
                "a lone cheating player is enough");
    }

    @Test
    void nullEntriesAreSkippedRatherThanCountedEitherWay() {
        assertEquals(SharedCarriageMode.NORMAL,
                SharedCarriageMode.of(Arrays.asList("alice", null), p -> p == null));
    }
}
