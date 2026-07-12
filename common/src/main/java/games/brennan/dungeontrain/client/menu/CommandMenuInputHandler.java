package games.brennan.dungeontrain.client.menu;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtRightClickBlock;
import org.lwjgl.glfw.GLFW;

/**
 * While the worldspace menu is open:
 * <ul>
 *   <li>All attack/use interactions are cancelled.</li>
 *   <li>The hovered entry activates on mouse <i>release</i>, not press —
 *       so a click that started outside the menu doesn't accidentally
 *       activate when its release lands on a hovered entry, and so the
 *       user can mouse-down, slide off, and release to cancel.</li>
 *   <li>PlayerInteractEvent variants are also cancelled as a belt-and-braces
 *       guard against right-click placement reaching block logic before the
 *       interaction key-mapping can stop it (the still-loader-specific
 *       {@code RightClickItem} / empty-click guards live in
 *       {@code platform.neoforge.CommandMenuInteractGuard}).</li>
 *   <li>When in typing mode, key presses feed the buffer.</li>
 * </ul>
 *
 * <p>Converted to the loader-neutral {@code DtEvents} seams (registered from
 * {@code NeoForgeClientEvents}); the three interactions still bound to a
 * loader-specific NeoForge event ({@code RightClickItem} cancel + the two
 * empty-click no-ops) are split into {@code CommandMenuInteractGuard} in the
 * root module.</p>
 */
public final class CommandMenuInputHandler {

    /**
     * True only when a press happened while the menu was open and we're
     * waiting for the matching release. A release without a prior in-menu
     * press (e.g. menu opened mid-click) is ignored.
     */
    private static boolean pressArmed = false;

    private CommandMenuInputHandler() {}

    public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
        if (!CommandMenuState.isOpen()) return;
        input.setCanceled(true);
        input.setSwingHand(false);
        pressArmed = true;
    }

    public static boolean onMouseButton(int button, int action, int modifiers) {
        if (!CommandMenuState.isOpen()) {
            pressArmed = false;
            return false;
        }
        if (net.minecraft.client.Minecraft.getInstance().screen != null) return false;
        int btn = button;
        if (btn != GLFW.GLFW_MOUSE_BUTTON_LEFT && btn != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        if (action != GLFW.GLFW_RELEASE) return false;
        if (!pressArmed) return false;
        pressArmed = false;

        int hovered = CommandMenuState.hoveredIdx();
        int hoveredSub = CommandMenuState.hoveredSubIdx();
        if (hovered >= 0) {
            CommandMenuState.activate(hovered, hoveredSub);
            return false;
        }
        int sideHovered = CommandMenuState.sideHoveredIdx();
        int sideHoveredSub = CommandMenuState.sideHoveredSubIdx();
        if (sideHovered >= 0) {
            CommandMenuState.activateSide(sideHovered, sideHoveredSub);
        }
        return false;
    }

    public static boolean onLeftClickBlock(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (CommandMenuState.isOpen()) return true;
        return false;
    }

    public static void onRightClickBlock(DtRightClickBlock event) {
        if (CommandMenuState.isOpen()) event.setCanceled(true);
    }

    public static void onKey(int key, int scanCode, int action, int modifiers) {
        if (!CommandMenuState.isOpen() || !CommandMenuState.typingMode()) return;
        // While a Screen is up (typically MenuTypingScreen, which we open in
        // beginTyping), keystrokes go through Screen.keyPressed / charTyped
        // and should not be double-processed here.
        if (net.minecraft.client.Minecraft.getInstance().screen != null) return;
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return;

        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> CommandMenuState.submitTyped();
            case GLFW.GLFW_KEY_ESCAPE -> CommandMenuState.cancelTyping();
            case GLFW.GLFW_KEY_BACKSPACE -> CommandMenuState.backspaceTyped();
            default -> appendIfAllowed(key, shift);
        }
    }

    /**
     * Narrow allowlist matching {@code CarriageVariant.NAME_PATTERN = [a-z0-9_]}.
     * Variant / plot / section names are the only free-text arguments in the
     * mod and all follow the same restriction, so rejecting anything outside
     * the allowlist is safe and gives immediate visual feedback that the
     * invalid key is ignored.
     */
    private static void appendIfAllowed(int key, boolean shift) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            char c = (char) ('a' + (key - GLFW.GLFW_KEY_A));
            CommandMenuState.appendTyped(c);
            return;
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            char c = (char) ('0' + (key - GLFW.GLFW_KEY_0));
            CommandMenuState.appendTyped(c);
            return;
        }
        if (key == GLFW.GLFW_KEY_MINUS && shift) {
            CommandMenuState.appendTyped('_');
        }
    }
}
