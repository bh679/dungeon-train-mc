package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
 *       guard against right-click placement reaching block logic before
 *       {@link InputEvent.InteractionKeyMappingTriggered} can stop it.</li>
 *   <li>When in typing mode, {@link InputEvent.Key} presses feed the buffer.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class CommandMenuInputHandler {

    /**
     * True only when a press happened while the menu was open and we're
     * waiting for the matching release. A release without a prior in-menu
     * press (e.g. menu opened mid-click) is ignored.
     */
    private static boolean pressArmed = false;

    private CommandMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!CommandMenuState.isOpen()) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!CommandMenuState.isOpen()) {
            pressArmed = false;
            return;
        }
        if (net.minecraft.client.Minecraft.getInstance().screen != null) return;
        int btn = event.getButton();
        if (btn != GLFW.GLFW_MOUSE_BUTTON_LEFT && btn != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        if (event.getAction() != GLFW.GLFW_RELEASE) return;
        if (!pressArmed) return;
        pressArmed = false;

        int hovered = CommandMenuState.hoveredIdx();
        int hoveredSub = CommandMenuState.hoveredSubIdx();
        if (hovered >= 0) {
            CommandMenuState.activate(hovered, hoveredSub);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (CommandMenuState.isOpen()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (CommandMenuState.isOpen()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // Not cancellable in 1.20.1, but we still want to note the menu ate the input.
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        // Not cancellable; equivalent to empty hand right-click.
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (CommandMenuState.isOpen()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!CommandMenuState.isOpen() || !CommandMenuState.typingMode()) return;
        // While a Screen is up (typically MenuTypingScreen, which we open in
        // beginTyping), keystrokes go through Screen.keyPressed / charTyped
        // and should not be double-processed here.
        if (net.minecraft.client.Minecraft.getInstance().screen != null) return;
        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return;

        int key = event.getKey();
        int modifiers = event.getModifiers();
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
