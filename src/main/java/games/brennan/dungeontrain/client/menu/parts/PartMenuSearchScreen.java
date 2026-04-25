package games.brennan.dungeontrain.client.menu.parts;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent capture screen that collects keystrokes into
 * {@link PartPositionMenu#searchBuffer}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.MenuTypingScreen} —
 * {@code Minecraft.screen != null} pauses every keybinding so typing
 * doesn't trigger movement, and the world keeps ticking because
 * {@link #isPauseScreen()} returns false.
 *
 * <p>The world-space panel keeps drawing underneath this invisible
 * screen, so the search results filter live as the player types. Press
 * {@link GLFW#GLFW_KEY_ENTER Enter} or {@link GLFW#GLFW_KEY_ESCAPE Escape}
 * to close the screen and resume world camera control — the search
 * buffer is preserved so the player can then aim at a result and click
 * to add it.</p>
 */
public final class PartMenuSearchScreen extends Screen {

    public PartMenuSearchScreen() {
        super(Component.literal("DT part-menu search"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> {
                onClose();
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                PartPositionMenu.backspaceSearch();
                return true;
            }
            default -> {}
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (c >= 'a' && c <= 'z') { PartPositionMenu.appendSearch(c); return true; }
        if (c >= 'A' && c <= 'Z') { PartPositionMenu.appendSearch((char) (c - 'A' + 'a')); return true; }
        if (c >= '0' && c <= '9') { PartPositionMenu.appendSearch(c); return true; }
        if (c == '_') { PartPositionMenu.appendSearch(c); return true; }
        return false;
    }
}
