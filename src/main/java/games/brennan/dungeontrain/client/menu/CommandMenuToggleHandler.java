package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Client tick handler that:
 * <ol>
 *   <li>Drains pending {@code [} key presses and toggles the menu open/closed.</li>
 *   <li>While open, runs the per-tick state maintenance (auto-close on
 *       distance, live entry rebuild) and the hover-raycast update.</li>
 * </ol>
 *
 * <p>Because the menu does not open a Minecraft
 * {@link net.minecraft.client.gui.screens.Screen},
 * {@link net.minecraft.client.KeyMapping#consumeClick()} fires for both
 * open and close presses — no need for a Screen-level keyPressed handler.</p>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class CommandMenuToggleHandler {

    private CommandMenuToggleHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();

        // If any other screen opens while our worldspace menu is up, close
        // it — otherwise the player can't look around (mouse captured by the
        // screen) and the floating menu becomes useless. Exempt our own
        // MenuTypingScreen though: beginTyping opens it intentionally to
        // suppress vanilla keybindings, and closing the menu here would
        // immediately cancel the typing field the user just activated.
        if (CommandMenuState.isOpen() && mc.screen != null
                && !(mc.screen instanceof MenuTypingScreen)) {
            CommandMenuState.close();
        }

        while (CommandMenuKeyBindings.TOGGLE.consumeClick()) {
            if (CommandMenuState.isOpen()) {
                CommandMenuState.close();
                continue;
            }
            tryOpen(mc);
        }

        if (CommandMenuState.isOpen()) {
            CommandMenuState.onClientTick();
            if (CommandMenuState.isOpen()) {
                CommandMenuRaycast.updateHovered();
                // stopDestroyBlock on every tick halts any accumulated
                // destroy progress. The hitResult clobber lives in the
                // renderer — it has to run after gameRenderer.pick() or
                // the next pick() overwrites it before continueAttack
                // reads it.
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
