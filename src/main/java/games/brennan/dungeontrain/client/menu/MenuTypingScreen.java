package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent capture screen opened while {@link CommandMenuState#typingMode}
 * is active. Its only purpose is to make {@code Minecraft.screen != null}
 * so vanilla's {@code KeyboardHandler.keyPress} short-circuits — that's the
 * idiomatic way to suppress every keybinding (movement, hotbar slot select,
 * inventory open, etc.) while the menu's typing field owns the keyboard.
 *
 * <p>Render is a no-op: the worldspace command menu's per-frame renderer
 * keeps drawing the typing field underneath this invisible screen, so the
 * player still sees the buffer they're editing.</p>
 *
 * <p>Allowlisted character set matches
 * {@link CommandMenuInputHandler#appendIfAllowed} ({@code [a-z0-9_]}) since
 * variant / part / contents names all match {@code ^[a-z0-9_]{1,32}$} on the
 * server side. Anything outside the allowlist is silently dropped.</p>
 */
public final class MenuTypingScreen extends Screen {

    public MenuTypingScreen() {
        super(Component.literal("DT typing"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;  // keep ticking the world / VS physics while typing
    }

    @Override
    protected void init() {
        // No widgets — the worldspace renderer draws the typing field.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally blank — leaves the worldspace HUD visible.
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                CommandMenuState.submitTyped();
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                CommandMenuState.backspaceTyped();
                return true;
            }
            default -> { /* fall through to super for ESC etc. */ }
        }
        // Default super.keyPressed handles ESC by closing the screen via
        // onClose() → removed() → cancelTyping in this class.
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (c >= 'a' && c <= 'z') { CommandMenuState.appendTyped(c); return true; }
        if (c >= 'A' && c <= 'Z') { CommandMenuState.appendTyped((char) (c - 'A' + 'a')); return true; }
        if (c >= '0' && c <= '9') { CommandMenuState.appendTyped(c); return true; }
        if (c == '_') { CommandMenuState.appendTyped(c); return true; }
        return false;
    }

    @Override
    public void removed() {
        // If the screen was dismissed externally (player hit ESC, or another
        // mod swapped screens), cancel typing so CommandMenuState's flag and
        // the worldspace menu's typing-row don't linger.
        if (CommandMenuState.typingMode()) {
            CommandMenuState.cancelTyping();
        }
        super.removed();
    }
}
