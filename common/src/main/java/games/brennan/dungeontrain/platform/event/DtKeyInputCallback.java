package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral form of NeoForge's {@code InputEvent.Key} (client game bus).
 * Fires on a raw keyboard-key transition <b>before</b> screen routing; DT
 * handlers guard {@code Minecraft.getInstance().screen} themselves (unchanged).
 * NeoForge's {@code InputEvent.Key} is <b>not</b> cancellable, so this callback is
 * {@code void}. Params mirror the GLFW key state the handlers read
 * ({@code getKey()}, {@code getScanCode()}, {@code getAction()},
 * {@code getModifiers()}) — the same four values Fabric's key callback provides.
 */
@FunctionalInterface
public interface DtKeyInputCallback {

    /**
     * @param key        GLFW key id ({@code event.getKey()})
     * @param scanCode   platform scan code ({@code event.getScanCode()})
     * @param action     GLFW action — press/release/repeat ({@code event.getAction()})
     * @param modifiers  GLFW modifier bitmask ({@code event.getModifiers()})
     */
    void onKey(int key, int scanCode, int action, int modifiers);
}
