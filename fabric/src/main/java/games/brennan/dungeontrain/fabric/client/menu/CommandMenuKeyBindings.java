package games.brennan.dungeontrain.fabric.client.menu;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Fabric twin of the NeoForge {@code client.menu.CommandMenuKeyBindings}: registers the
 * {@code X} toggle for the worldspace command menu. NeoForge's version uses
 * {@code KeyConflictContext.IN_GAME} (a NeoForge-only KeyMapping overload) to suppress the
 * bind inside chat/inventory; the vanilla {@link KeyMapping} used here has no such context,
 * so {@code CommandMenuToggleHandler} self-guards on {@code Minecraft.screen} instead
 * (which it already does). Same key + category as NeoForge.
 */
public final class CommandMenuKeyBindings {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final KeyMapping TOGGLE = new KeyMapping(
        "key.dungeontrain.command_menu",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_X,
        "key.categories.dungeontrain");

    private CommandMenuKeyBindings() {}

    public static void onRegisterKeys(java.util.function.Consumer<KeyMapping> registrar) {
        registrar.accept(TOGGLE);
        LOGGER.info("Command menu keymapping registered (default: X)");
    }
}
