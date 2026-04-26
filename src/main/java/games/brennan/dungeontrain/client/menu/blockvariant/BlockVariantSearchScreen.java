package games.brennan.dungeontrain.client.menu.blockvariant;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent capture screen that collects keystrokes into
 * {@link BlockVariantMenu#searchBuffer}. Mirrors the part-menu
 * {@link games.brennan.dungeontrain.client.menu.parts.PartMenuSearchScreen}
 * — accepts a-z, 0-9, underscore, colon (for {@code modid:name}), forward
 * slash, hyphen, period; backspace; Enter / Escape to close. The world-space
 * panel keeps drawing underneath since {@link #isPauseScreen()} returns false.
 */
public final class BlockVariantSearchScreen extends Screen {

    public BlockVariantSearchScreen() {
        super(Component.literal("DT block-variant search"));
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
                BlockVariantMenu.backspaceSearch();
                return true;
            }
            default -> {}
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (c >= 'a' && c <= 'z') { BlockVariantMenu.appendSearch(c); return true; }
        if (c >= 'A' && c <= 'Z') { BlockVariantMenu.appendSearch((char) (c - 'A' + 'a')); return true; }
        if (c >= '0' && c <= '9') { BlockVariantMenu.appendSearch(c); return true; }
        // Block IDs are like "minecraft:stone_bricks" — these chars matter for filter precision.
        if (c == '_' || c == ':' || c == '/' || c == '-' || c == '.') {
            BlockVariantMenu.appendSearch(c);
            return true;
        }
        return false;
    }
}
