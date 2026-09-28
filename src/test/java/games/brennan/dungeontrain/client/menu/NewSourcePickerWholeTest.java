package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Whole room / group "+ New" picker. Both menus used to hand back no picker at all, so the
 * button was a dead click; what's pinned here is that each kind reaches its own {@code whole … new}
 * verb and that Current only appears when there is a plot of that kind to copy.
 */
@ExtendWith(MenuTestLanguage.class)
final class NewSourcePickerWholeTest {

    private static List<CommandMenuEntry> entries(NewSourcePickerScreen.Category category, String currentId) {
        return new NewSourcePickerScreen(category, null, currentId).entries();
    }

    private static CommandMenuEntry.TypeArg typeArg(List<CommandMenuEntry> entries, int index) {
        return assertInstanceOf(CommandMenuEntry.TypeArg.class, entries.get(index));
    }

    @Test
    @DisplayName("Room: Blank then Current, through `whole new`")
    void room_blankAndCurrent() {
        List<CommandMenuEntry> e = entries(NewSourcePickerScreen.Category.WHOLE, "bistro");

        assertEquals("dungeontrain editor whole new", typeArg(e, 0).commandPrefix());
        assertEquals("blank", typeArg(e, 0).commandSuffix());
        assertEquals("Current (bistro)", typeArg(e, 1).label());
        assertEquals("dungeontrain editor whole new", typeArg(e, 1).commandPrefix());
        assertEquals("bistro", typeArg(e, 1).commandSuffix());
        assertInstanceOf(CommandMenuEntry.Back.class, e.get(e.size() - 1));
    }

    @Test
    @DisplayName("Group: same shape through `whole group new`")
    void group_routesThroughGroupVerb() {
        List<CommandMenuEntry> e = entries(NewSourcePickerScreen.Category.WHOLE_GROUP, "convoy");

        assertEquals("dungeontrain editor whole group new", typeArg(e, 0).commandPrefix());
        assertEquals("dungeontrain editor whole group new", typeArg(e, 1).commandPrefix());
        assertEquals("convoy", typeArg(e, 1).commandSuffix());
    }

    @Test
    @DisplayName("Nothing of that kind to copy: Blank only")
    void noCurrent_blankOnly() {
        for (NewSourcePickerScreen.Category c : List.of(
                NewSourcePickerScreen.Category.WHOLE, NewSourcePickerScreen.Category.WHOLE_GROUP)) {
            List<CommandMenuEntry> e = entries(c, "");
            assertEquals("blank", typeArg(e, 0).commandSuffix());
            assertTrue(e.stream().noneMatch(x -> x instanceof CommandMenuEntry.TypeArg t
                && t.label().startsWith("Current")), c + ": no Current row");
        }
    }
}
