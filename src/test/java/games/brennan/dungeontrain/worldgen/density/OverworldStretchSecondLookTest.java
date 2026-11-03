package games.brennan.dungeontrain.worldgen.density;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rare biomes' second look ({@link OverworldStretchBiomes#withSecondLook}), on plain strings. */
class OverworldStretchSecondLookTest {

    private static final Predicate<String> RARE = Set.of("tropics", "hot_springs")::contains;
    private static final Predicate<String> LAND = s -> !s.equals("ocean") && !s.equals("lush_caves");

    private static String look(String base, String shifted) {
        return OverworldStretchBiomes.withSecondLook(base, () -> shifted, RARE, LAND);
    }

    @Test
    @DisplayName("ordinary land takes a rare shifted pick, and keeps itself otherwise")
    void landTakesRare() {
        assertEquals("tropics", look("plains", "tropics"));
        assertEquals("plains", look("plains", "forest"));
        assertEquals("plains", look("plains", null));
    }

    @Test
    @DisplayName("water, caves and an already-rare pick keep their own biome, without a second sample")
    void othersKeepTheirOwn() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<String> counted = () -> { asked.incrementAndGet(); return "tropics"; };
        assertEquals("ocean", OverworldStretchBiomes.withSecondLook("ocean", counted, RARE, LAND));
        assertEquals("lush_caves", OverworldStretchBiomes.withSecondLook("lush_caves", counted, RARE, LAND));
        assertEquals("hot_springs", OverworldStretchBiomes.withSecondLook("hot_springs", counted, RARE, LAND));
        assertEquals(0, asked.get());
    }
}
