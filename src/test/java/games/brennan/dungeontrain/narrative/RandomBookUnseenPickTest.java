package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unseen-pick behind the random-book swap: it only ever hands out a tuple the seen test rejects. */
final class RandomBookUnseenPickTest {

    private static RandomBookFile book(String name, double weight, int variants) {
        return new RandomBookFile(ResourceLocation.fromNamespaceAndPath("dungeontrain", name), name, "Faulthurst",
            0, weight, java.util.Collections.nCopies(variants, "text"));
    }

    private static final RandomBookFile MUSINGS = book("musings_of_faulthurst", 1, 4);
    private static final RandomBookFile ASIDES = book("faulthursts_asides_i", 1, 3);

    @Test
    @DisplayName("with one tuple left unseen, every seed picks it")
    void onlyUnseenTuple() {
        RandomBookFactory.SeenTest seen = (b, i) -> !(b.equals("faulthursts_asides_i") && i == 2);
        for (long seed = 0; seed < 200; seed++) {
            Optional<RandomBookFactory.PickedBook> pick =
                RandomBookFactory.tryPickUnseen(List.of(MUSINGS, ASIDES), seen, seed * 31, seed * 17);
            assertTrue(pick.isPresent());
            assertEquals("faulthursts_asides_i", pick.get().book().basename());
            assertEquals(2, pick.get().variantIndex());
        }
    }

    @Test
    @DisplayName("never picks a seen tuple")
    void neverSeen() {
        Set<Integer> seenMusings = Set.of(0, 2);
        RandomBookFactory.SeenTest seen = (b, i) -> b.equals("musings_of_faulthurst") && seenMusings.contains(i);
        for (long seed = 0; seed < 200; seed++) {
            RandomBookFactory.PickedBook pick = RandomBookFactory
                .tryPickUnseen(List.of(MUSINGS, ASIDES), seen, seed * 7919, seed * 104729).orElseThrow();
            assertTrue(!seen.seen(pick.book().basename(), pick.variantIndex()));
        }
    }

    @Test
    @DisplayName("everything seen, or no weighted book, picks nothing")
    void allSeen() {
        assertTrue(RandomBookFactory.tryPickUnseen(List.of(MUSINGS, ASIDES), (b, i) -> true, 1, 2).isEmpty());
        assertTrue(RandomBookFactory.tryPickUnseen(List.of(book("unweighted", 0, 2)), (b, i) -> false, 1, 2).isEmpty());
    }
}
