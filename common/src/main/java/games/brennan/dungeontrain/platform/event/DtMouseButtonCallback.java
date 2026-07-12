package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code InputEvent.MouseButton.Pre} (client game
 * bus). Fires on a raw mouse-button transition <b>before</b> screen routing (DT
 * handlers guard {@code Minecraft.getInstance().screen} themselves — unchanged).
 * CANCELLABLE: the callback returns {@code true} to swallow the button (the former
 * {@code event.setCanceled(true)}); {@code NeoForgeClientInputBridge} stops on the
 * first {@code true} and cancels. Params mirror the GLFW mouse state the handlers
 * read.
 */
@FunctionalInterface
public interface DtMouseButtonCallback {

    /**
     * @param button    GLFW mouse-button id ({@code event.getButton()})
     * @param action    GLFW action — press/release ({@code event.getAction()})
     * @param modifiers  GLFW modifier bitmask ({@code event.getModifiers()})
     * @return {@code true} to cancel the button (suppress vanilla handling)
     */
    boolean onMouseButton(int button, int action, int modifiers);
}
