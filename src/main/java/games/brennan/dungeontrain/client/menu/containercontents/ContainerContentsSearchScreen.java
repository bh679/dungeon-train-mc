package games.brennan.dungeontrain.client.menu.containercontents;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent capture screen feeding {@link ContainerContentsMenu#searchBuffer}.
 * Mirrors {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantSearchScreen}.
 * The world-space panel keeps drawing underneath since
 * {@link #isPauseScreen()} returns false.
 */
public final class ContainerContentsSearchScreen extends Screen {

    public ContainerContentsSearchScreen() {
        super(Component.literal("DT container-contents search"));
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
                ContainerContentsMenu.backspaceSearch();
                return true;
            }
            default -> {}
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (c >= 'a' && c <= 'z') { ContainerContentsMenu.appendSearch(c); return true; }
        if (c >= 'A' && c <= 'Z') { ContainerContentsMenu.appendSearch((char) (c - 'A' + 'a')); return true; }
        if (c >= '0' && c <= '9') { ContainerContentsMenu.appendSearch(c); return true; }
        if (c == '_' || c == ':' || c == '/' || c == '-' || c == '.') {
            ContainerContentsMenu.appendSearch(c);
            return true;
        }
        return false;
    }
}
