package games.brennan.dungeontrain.cheat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HiddenCommands#prune}: hidden roots leave the tree, the rest still parse and suggest. */
class HiddenCommandsTest {

    private static CommandDispatcher<Object> tree(String... roots) {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        for (String r : roots) d.register(LiteralArgumentBuilder.<Object>literal(r).executes(c -> 1));
        return d;
    }

    private static List<String> suggest(CommandDispatcher<Object> d, String typed) throws Exception {
        return d.getCompletionSuggestions(d.parse(typed, new Object())).get().getList()
            .stream().map(Suggestion::getText).sorted().toList();
    }

    @Test
    @DisplayName("Hidden roots vanish from children and from suggestions; the rest stay")
    void prunesHiddenRoots() throws Exception {
        CommandDispatcher<Object> d = tree("bug", "fixconfig", "streamer", "give", "liveappeal");
        assertEquals(3, HiddenCommands.prune(d.getRoot()));
        assertNull(d.getRoot().getChild("fixconfig"));
        assertNull(d.getRoot().getChild("streamer"));
        assertNull(d.getRoot().getChild("liveappeal"));
        assertNotNull(d.getRoot().getChild("bug"));
        assertNotNull(d.getRoot().getChild("give"));
        assertEquals(List.of("bug", "give"), suggest(d, ""));
        assertFalse(d.parse("bug", new Object()).getContext().getNodes().isEmpty(), "bug still parses");
        assertTrue(d.parse("fixconfig", new Object()).getContext().getNodes().isEmpty(), "fixconfig no longer parses");
    }

    @Test
    @DisplayName("Every hidden root is removed, and pruning twice is harmless")
    void allHiddenRootsAndIdempotent() {
        CommandDispatcher<Object> d = tree(HiddenCommands.HIDDEN_ROOTS.toArray(String[]::new));
        assertEquals(HiddenCommands.HIDDEN_ROOTS.size(), HiddenCommands.prune(d.getRoot()));
        assertEquals(0, HiddenCommands.prune(d.getRoot()));
        assertTrue(d.getRoot().getChildren().isEmpty());
    }

    @Test
    @DisplayName("/bug and the op roots are never on the hidden list")
    void bugNeverHidden() {
        assertFalse(HiddenCommands.HIDDEN_ROOTS.contains("bug"));
        assertFalse(HiddenCommands.HIDDEN_ROOTS.contains("advancement"));
        assertFalse(HiddenCommands.HIDDEN_ROOTS.contains("dungeontrain"));
    }
}
