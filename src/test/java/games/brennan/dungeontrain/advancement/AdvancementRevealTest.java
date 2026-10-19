package games.brennan.dungeontrain.advancement;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AdvancementReveal")
class AdvancementRevealTest {

    private static final Map<String, Optional<String>> TITLES = Map.of(
        "b", Optional.of("Bravo"),
        "a", Optional.of("Alpha"),
        "done", Optional.of("Already earned"),
        "recipe", Optional.empty());

    @Test
    @DisplayName("keeps unearned, titled advancements, sorted by title")
    void selectsUnearnedTitled() {
        List<String> revealed = AdvancementReveal.select(List.of("b", "done", "recipe", "a"),
            Set.of("done")::contains, TITLES::get);
        assertEquals(List.of("a", "b"), revealed);
    }

    @Test
    @DisplayName("nothing revealed means no line")
    void emptyMeansNoMessage() {
        assertTrue(AdvancementReveal.message(List.of()).isEmpty());
    }

    @Test
    @DisplayName("lists names after the reveal key")
    void listsNames() {
        Component line = AdvancementReveal.message(List.of(Component.literal("A"), Component.literal("B"))).orElseThrow();
        TranslatableContents contents = (TranslatableContents) line.getContents();
        assertEquals(AdvancementReveal.KEY_LINE, contents.getKey());
        assertEquals("A, B", ((Component) contents.getArgs()[0]).getString());
    }

    @Test
    @DisplayName("past the cap, the rest collapse into a +N more count")
    void capsNames() {
        List<Component> names = new ArrayList<>();
        for (int i = 0; i < AdvancementReveal.MAX_NAMED + 3; i++) names.add(Component.literal("n" + i));
        Component list = (Component) ((TranslatableContents) AdvancementReveal.message(names).orElseThrow()
            .getContents()).getArgs()[0];
        Component more = list.getSiblings().get(list.getSiblings().size() - 1);
        TranslatableContents moreContents = (TranslatableContents) more.getContents();
        assertEquals(AdvancementReveal.KEY_MORE, moreContents.getKey());
        assertEquals(3, moreContents.getArgs()[0]);
        assertTrue(list.getString().startsWith("n0, n1"));
    }
}
