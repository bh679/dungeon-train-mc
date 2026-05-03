package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the command strings produced by {@link CarriageContentsAllowScreen}
 * so the contents toggle round-trip stays in lock-step with
 * {@code /dungeontrain editor carriage-contents <variant> <contents> on|off}.
 */
final class CarriageContentsAllowScreenTest {

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
    }

    @Test
    @DisplayName("entries: at least one Toggle row per registered content + a Back row")
    void entries_oneTogglePerContent() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("standard");
        List<CommandMenuEntry> entries = screen.entries();
        int contentsCount = CarriageContentsRegistry.allContents().size();
        assertEquals(contentsCount + 1, entries.size(),
            "one Toggle per content plus a Back row");
        for (int i = 0; i < contentsCount; i++) {
            assertInstanceOf(CommandMenuEntry.Toggle.class, entries.get(i),
                "entry " + i + " should be a Toggle");
        }
        assertInstanceOf(CommandMenuEntry.Back.class, entries.get(contentsCount));
    }

    @Test
    @DisplayName("Toggle row pins the on/off command shape — splices variantId and contentsId")
    void toggle_commandStrings() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("standard");
        List<CommandMenuEntry> entries = screen.entries();
        CommandMenuEntry.Toggle defaultRow = (CommandMenuEntry.Toggle) entries.get(0);
        assertEquals("default", defaultRow.label());
        assertEquals("dungeontrain editor carriage-contents standard default on", defaultRow.cmdToTurnOn());
        assertEquals("dungeontrain editor carriage-contents standard default off", defaultRow.cmdToTurnOff());
    }

    @Test
    @DisplayName("Custom variant id is spliced verbatim into both commands")
    void toggle_customVariant() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("my_custom");
        CommandMenuEntry.Toggle row = (CommandMenuEntry.Toggle) screen.entries().get(0);
        assertEquals("dungeontrain editor carriage-contents my_custom default on", row.cmdToTurnOn());
        assertEquals("dungeontrain editor carriage-contents my_custom default off", row.cmdToTurnOff());
    }

    @Test
    @DisplayName("Custom content registered after construction appears in the next entries() rebuild")
    void entries_picksUpNewlyRegisteredCustoms() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("standard");
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("loot_room"));
        List<CommandMenuEntry> entries = screen.entries();
        boolean foundLootRoom = false;
        for (CommandMenuEntry e : entries) {
            if (e instanceof CommandMenuEntry.Toggle t && "loot_room".equals(t.label())) {
                foundLootRoom = true;
                assertEquals("dungeontrain editor carriage-contents standard loot_room on", t.cmdToTurnOn());
                assertEquals("dungeontrain editor carriage-contents standard loot_room off", t.cmdToTurnOff());
                break;
            }
        }
        assertTrue(foundLootRoom, "newly registered loot_room should appear in the rebuilt entries list");
    }

    @Test
    @DisplayName("title is 'Contents' for the breadcrumb band")
    void title_isContents() {
        assertEquals("Contents", new CarriageContentsAllowScreen("standard").title());
    }
}
