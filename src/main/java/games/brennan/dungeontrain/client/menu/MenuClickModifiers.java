package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Which modifier keys are held at the moment an editor panel cell is clicked.
 *
 * <p>The world-space panels are drawn as HUD overlays with no {@link Screen} of their own, and
 * {@code Screen.hasShiftDown()} and friends are unreliable there — which is why each world-space
 * input handler samples GLFW directly for shift. This does the same for the command modifier, and
 * picks the right source itself so the world-space and screen-space dispatch paths can share one
 * call without threading another flag through every {@code click} / {@code dispatch} signature.</p>
 *
 * <p>"Command" means Cmd on macOS and Ctrl elsewhere, and both are accepted on either platform —
 * vanilla's {@link Screen#hasControlDown()} already makes that swap, and the GLFW poll below
 * covers Super as well as Control so the fallback path matches.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MenuClickModifiers {

    private MenuClickModifiers() {}

    /** True while Cmd (macOS) or Ctrl is held. */
    public static boolean cmdDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return Screen.hasControlDown();
        long win = mc.getWindow().getWindow();
        return isDown(win, GLFW.GLFW_KEY_LEFT_CONTROL)
            || isDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL)
            || isDown(win, GLFW.GLFW_KEY_LEFT_SUPER)
            || isDown(win, GLFW.GLFW_KEY_RIGHT_SUPER);
    }

    private static boolean isDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
