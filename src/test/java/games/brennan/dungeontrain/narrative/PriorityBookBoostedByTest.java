package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Who boosted an author's book: the newest few by name, the rest as a count, nobody as a count alone. */
class PriorityBookBoostedByTest {

    private static TranslatableContents tr(Component c) {
        return (TranslatableContents) c.getContents();
    }

    @Test
    void namesUpToThreeBoosters() {
        TranslatableContents t = tr(PriorityBookMessage.boostedBy("en_us", List.of("Alex", "Sam"), 4));
        assertEquals("chat.dungeontrain.priority_book.boosted_by", t.getKey());
        assertEquals("Alex, Sam", ((Component) t.getArgs()[0]).getString());
    }

    @Test
    void countsTheRestBeyondThree() {
        TranslatableContents t = tr(PriorityBookMessage.boostedBy("en_us", List.of("A", "B", "C", "D", "E"), 9));
        assertEquals("chat.dungeontrain.priority_book.boosted_by_more", t.getKey());
        assertEquals("A, B, C", ((Component) t.getArgs()[0]).getString());
        assertEquals(2, t.getArgs()[1]);
    }

    @Test
    void noNamesFallsBackToTheCount() {
        TranslatableContents t = tr(PriorityBookMessage.boostedBy("en_us", List.of(), 2));
        assertEquals("chat.dungeontrain.priority_book.boosted_by_anon.other", t.getKey());
    }
}
