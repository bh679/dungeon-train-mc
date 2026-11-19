package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The press-arm / release-fire rule every world-space panel's clicks follow. */
final class WorldPanelInputArmTest {

    private static final int L = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    private static final int R = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    private static final int M = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;

    @Test
    @DisplayName("only the armed button's release fires")
    void sameButtonRelease() {
        WorldPanelInput.Arm arm = WorldPanelInput.Arm.pressed(L, false);
        assertTrue(arm.firesOn(L, GLFW.GLFW_RELEASE, true));
        assertFalse(arm.firesOn(R, GLFW.GLFW_RELEASE, true), "the other button's release is not this press");
        assertFalse(arm.firesOn(L, GLFW.GLFW_PRESS, true), "a press is not a release");
        assertFalse(WorldPanelInput.Arm.IDLE.firesOn(L, GLFW.GLFW_RELEASE, true), "nothing armed, nothing fires");
    }

    @Test
    @DisplayName("a right-click press fires on a right release only where the panel takes right-clicks")
    void rightClick() {
        WorldPanelInput.Arm arm = WorldPanelInput.Arm.pressed(R, true);
        assertTrue(arm.firesOn(R, GLFW.GLFW_RELEASE, true));
        assertFalse(arm.firesOn(R, GLFW.GLFW_RELEASE, false), "a left-only panel never fires a right release");
        assertTrue(arm.shift(), "shift at press time is carried to the fire");
    }

    @Test
    @DisplayName("the middle button is never a panel click")
    void middleButton() {
        assertFalse(WorldPanelInput.Arm.pressed(M, false).firesOn(M, GLFW.GLFW_RELEASE, true));
    }
}
