package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the Test-the-Carriage save prompt matches a dirty row to the room the player is standing
 * in. The keying is the one thing that can drift away from
 * {@link EditorDirtyCheck}'s portal-room scan silently: a mismatch reads as "clean", so the prompt
 * would never appear and the author would go on testing their last save without knowing.
 */
final class PortalTestSaveCheckScreenTest {

    private static EditorDirtyCheck.DirtyEntry portalRow(String roomName) {
        return new EditorDirtyCheck.DirtyEntry("portals", "portal_room." + roomName,
            "dimensional carriage / " + roomName, true, false);
    }

    @Test
    @DisplayName("Dirty key matches the portal-room scan's modelId format")
    void dirtyKey_matchesScanFormat() {
        assertEquals("portal_room.crypt_hall", PortalTestSaveCheckScreen.dirtyKey("crypt_hall"));
    }

    @Test
    @DisplayName("A row for this room reads dirty")
    void isDirty_matchesOwnRoom() {
        assertTrue(PortalTestSaveCheckScreen.isDirty(List.of(portalRow("crypt_hall")), "crypt_hall"));
    }

    @Test
    @DisplayName("A row for another room does not — the prompt is scoped to one carriage")
    void isDirty_ignoresOtherRooms() {
        assertFalse(PortalTestSaveCheckScreen.isDirty(List.of(portalRow("brass_vault")), "crypt_hall"));
    }

    @Test
    @DisplayName("A same-named row in another category does not match")
    void isDirty_ignoresOtherCategories() {
        EditorDirtyCheck.DirtyEntry carriage = new EditorDirtyCheck.DirtyEntry(
            "carriages", "portal_room.crypt_hall", "crypt_hall", true, false);
        assertFalse(PortalTestSaveCheckScreen.isDirty(List.of(carriage), "crypt_hall"));
    }

    @Test
    @DisplayName("Unpromoted-only rows aren't unsaved work — no prompt for them")
    void isDirty_ignoresUnpromotedOnly() {
        EditorDirtyCheck.DirtyEntry unpromoted = new EditorDirtyCheck.DirtyEntry(
            "portals", "portal_room.crypt_hall", "dimensional carriage / crypt_hall", false, true);
        assertFalse(PortalTestSaveCheckScreen.isDirty(List.of(unpromoted), "crypt_hall"));
    }

    @Test
    @DisplayName("An empty list, and a plot with no room name, both read clean")
    void isDirty_cleanCases() {
        assertFalse(PortalTestSaveCheckScreen.isDirty(List.of(), "crypt_hall"));
        assertFalse(PortalTestSaveCheckScreen.isDirty(List.of(portalRow("crypt_hall")), ""));
    }
}
