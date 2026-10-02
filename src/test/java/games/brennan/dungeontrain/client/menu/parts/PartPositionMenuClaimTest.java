package games.brennan.dungeontrain.client.menu.parts;

import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu.CellKind;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu.Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PartPositionMenu#claimsPointer}: when the parts menu makes the other panels stand down. */
final class PartPositionMenuClaimTest {

    @Test
    @DisplayName("an open parts menu the crosshair is not on leaves clicks to the other panels")
    void openButNotPointedAt() {
        assertFalse(PartPositionMenu.claimsPointer(true, Hit.NONE),
            "a menu left open in a plot the player has walked away from must not silence the rest");
        assertFalse(PartPositionMenu.claimsPointer(true, null));
    }

    @Test
    @DisplayName("the crosshair on any of its cells claims the click")
    void pointedAt() {
        for (CellKind kind : CellKind.values()) {
            if (kind == CellKind.NONE) continue;
            assertTrue(PartPositionMenu.claimsPointer(true, new Hit(kind, 0)), kind.name());
        }
    }

    @Test
    @DisplayName("a closed menu claims nothing, whatever hover it last held")
    void closed() {
        assertFalse(PartPositionMenu.claimsPointer(false, Hit.NONE));
        assertFalse(PartPositionMenu.claimsPointer(false, new Hit(CellKind.ENTRY_WEIGHT, 2)));
    }
}
