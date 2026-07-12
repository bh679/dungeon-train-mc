package games.brennan.dungeontrain.fabric.client.menu;

import games.brennan.dungeontrain.client.menu.CommandMenuRaycast;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.MenuTypingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Fabric twin of the NeoForge {@code client.menu.CommandMenuToggleHandler} (which is
 * root-only because it reads the loader-specific {@code CommandMenuKeyBindings.TOGGLE}).
 * Identical logic; references the Fabric {@link CommandMenuKeyBindings#TOGGLE}. All menu
 * state ({@code CommandMenuState}, {@code MenuTypingScreen}, {@code CommandMenuRaycast})
 * is loader-neutral :common.
 */
public final class CommandMenuToggleHandler {

    private CommandMenuToggleHandler() {}

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();

        if (CommandMenuState.isOpen() && mc.screen != null
                && !(mc.screen instanceof MenuTypingScreen)) {
            CommandMenuState.close();
        }

        while (CommandMenuKeyBindings.TOGGLE.consumeClick()) {
            if (CommandMenuState.isOpen()) {
                CommandMenuState.close();
                continue;
            }
            boolean creative = mc.player != null && mc.player.isCreative();
            if (!creative && !Screen.hasShiftDown()) continue;
            tryOpen(mc);
        }

        if (CommandMenuState.isOpen()) {
            CommandMenuState.onClientTick();
            if (CommandMenuState.isOpen()) {
                CommandMenuRaycast.updateHovered();
                if (mc.gameMode != null) {
                    mc.gameMode.stopDestroyBlock();
                }
            }
        }
    }

    private static void tryOpen(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return;
        CommandMenuState.open();
    }
}
