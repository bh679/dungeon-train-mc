package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Registers the {@code X} toggle for the worldspace command menu. The
 * {@link KeyConflictContext#IN_GAME} context means typing {@code X} inside
 * chat or an inventory screen does NOT fire this — only bare in-game input.
 */
public final class CommandMenuKeyBindings {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final KeyMapping TOGGLE = new KeyMapping(
        "key.dungeontrain.command_menu",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_X,
        "key.categories.dungeontrain"
    );

    private CommandMenuKeyBindings() {}

        public static void onRegisterKeys(java.util.function.Consumer<net.minecraft.client.KeyMapping> registrar) {
        registrar.accept(TOGGLE);
        LOGGER.info("Command menu keymapping registered (default: X)");
    }
}
