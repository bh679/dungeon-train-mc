package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.net.ContentsAllowSyncPacket;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the command strings produced by {@link CarriageContentsAllowScreen}
 * so the contents toggle round-trip stays in lock-step with
 * {@code /dungeontrain editor carriage-contents <variant> <contents> on|off}.
 */
@ExtendWith(MenuTestLanguage.class)
final class CarriageContentsAllowScreenTest {

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
    }

    /** Every registered content, none off — what the server answers for a carriage with no sidecar. */
    private static ContentsAllowSyncPacket allOn(CarriageContentsAllowScreen screen) {
        List<String> ids = new ArrayList<>();
        for (CarriageContents c : CarriageContentsRegistry.allContents()) ids.add(c.id());
        return new ContentsAllowSyncPacket(screen.kind(), screen.variantId(), ids, List.of());
    }

    /** The screen's rows for the all-on server answer — {@code entries()} minus the network ask. */
    private static List<CommandMenuEntry> rows(CarriageContentsAllowScreen screen) {
        return screen.entriesFrom(allOn(screen));
    }

    @Test
    @DisplayName("Before the server answers, the screen shows Loading + Back rather than guessing")
    void entries_loadingBeforeAnswer() {
        List<CommandMenuEntry> entries = new CarriageContentsAllowScreen("standard").entriesFrom(null);
        assertEquals(2, entries.size());
        assertInstanceOf(CommandMenuEntry.Loading.class, entries.get(0));
        assertInstanceOf(CommandMenuEntry.Back.class, entries.get(1));
    }

    @Test
    @DisplayName("A row the server reports off (excluded, or opt-in and never switched on) shows OFF")
    void entries_offRowsShowOff() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("standard");
        List<CommandMenuEntry> entries = screen.entriesFrom(new ContentsAllowSyncPacket(
            screen.kind(), "standard", List.of("default", "new_room"), List.of("new_room")));
        assertTrue(((CommandMenuEntry.Toggle) entries.get(0)).state());
        assertFalse(((CommandMenuEntry.Toggle) entries.get(1)).state());
    }

    @Test
    @DisplayName("entries: at least one Toggle row per registered content + a Back row")
    void entries_oneTogglePerContent() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("standard");
        List<CommandMenuEntry> entries = rows(screen);
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
        List<CommandMenuEntry> entries = rows(screen);
        CommandMenuEntry.Toggle defaultRow = (CommandMenuEntry.Toggle) entries.get(0);
        assertEquals("default", defaultRow.label());
        assertEquals("dungeontrain editor carriage-contents standard default on", defaultRow.cmdToTurnOn());
        assertEquals("dungeontrain editor carriage-contents standard default off", defaultRow.cmdToTurnOff());
    }

    @Test
    @DisplayName("Custom variant id is spliced verbatim into both commands")
    void toggle_customVariant() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("my_custom");
        CommandMenuEntry.Toggle row = (CommandMenuEntry.Toggle) rows(screen).get(0);
        assertEquals("dungeontrain editor carriage-contents my_custom default on", row.cmdToTurnOn());
        assertEquals("dungeontrain editor carriage-contents my_custom default off", row.cmdToTurnOff());
    }

    @Test
    @DisplayName("Custom content registered after construction appears in the next entries() rebuild")
    void entries_picksUpNewlyRegisteredCustoms() {
        CarriageContentsAllowScreen screen = new CarriageContentsAllowScreen("standard");
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("loot_room"));
        List<CommandMenuEntry> entries = rows(screen);
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

    // ---- the portal-room face of the same screen ----

    @Test
    @DisplayName("forPortalRoom targets the room subcommand, splicing the room NAME")
    void portalRoom_commandStrings() {
        CarriageContentsAllowScreen screen = CarriageContentsAllowScreen.forPortalRoom("window_contents");
        CommandMenuEntry.Toggle row = (CommandMenuEntry.Toggle) rows(screen).get(0);
        assertEquals("default", row.label());
        assertEquals("dungeontrain editor portal-room-contents window_contents default on",
            row.cmdToTurnOn());
        assertEquals("dungeontrain editor portal-room-contents window_contents default off",
            row.cmdToTurnOff());
    }

    @Test
    @DisplayName("forCarriage is the plain constructor — the carriage command is unchanged")
    void carriageFactory_matchesConstructor() {
        CommandMenuEntry.Toggle viaFactory = (CommandMenuEntry.Toggle)
            rows(CarriageContentsAllowScreen.forCarriage("standard")).get(0);
        CommandMenuEntry.Toggle viaCtor = (CommandMenuEntry.Toggle)
            rows(new CarriageContentsAllowScreen("standard")).get(0);
        assertEquals(viaCtor.cmdToTurnOn(), viaFactory.cmdToTurnOn());
        assertEquals(viaCtor.cmdToTurnOff(), viaFactory.cmdToTurnOff());
        assertEquals("dungeontrain editor carriage-contents standard default on",
            viaFactory.cmdToTurnOn());
    }

    @Test
    @DisplayName("Both faces share the row set and the title — only the subcommand differs")
    void bothFacesShareEverythingElse() {
        CarriageContentsAllowScreen room = CarriageContentsAllowScreen.forPortalRoom("book");
        CarriageContentsAllowScreen carriage = CarriageContentsAllowScreen.forCarriage("standard");
        assertEquals(rows(carriage).size(), rows(room).size());
        assertEquals("Contents", room.title());
        assertEquals("book", room.variantId());
    }
}
