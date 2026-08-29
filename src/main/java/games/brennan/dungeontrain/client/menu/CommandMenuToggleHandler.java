package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

/**
 * Client tick handler that:
 * <ol>
 *   <li>Drains pending toggle-key presses and opens the menu.</li>
 *   <li>While open, runs the per-tick state maintenance (auto-close on
 *       distance, live entry rebuild).</li>
 * </ol>
 *
 * <p>This handler only ever <i>opens</i> the menu. Once
 * {@link CommandMenuGuiScreen} is up, vanilla stops polling keybindings, so
 * {@link net.minecraft.client.KeyMapping#consumeClick()} cannot fire again —
 * the screen matches the toggle key itself in {@code keyPressed} to close.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class CommandMenuToggleHandler {

    private CommandMenuToggleHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {

        Minecraft mc = Minecraft.getInstance();

        // Screen-space: the menu IS a screen, so its own screen being up is the normal state.
        // What still matters is another screen replacing ours — the inventory, a sign, another
        // mod's GUI — which would leave our state open with nothing rendering it.
        //
        // World-space: no screen of ours is up at all, except the invisible MenuTypingScreen
        // while the player is typing a row's argument. Any OTHER screen means something took
        // over the display, and a world-space panel drawn behind an inventory is just clutter.
        if (CommandMenuState.isOpen() && mc.screen != null
                && !(mc.screen instanceof CommandMenuGuiScreen)
                && !(mc.screen instanceof MenuTypingScreen)) {
            CommandMenuState.close();
        }

        // The menu is a creative-only tool. If the player drops out of
        // creative while it is up — the editor exit path restores the
        // pre-session game mode — close it rather than leave a creative
        // menu floating in survival.
        if (CommandMenuState.isOpen()
                && (mc.player == null || !mc.player.isCreative())) {
            CommandMenuState.close();
        }

        while (CommandMenuKeyBindings.TOGGLE.consumeClick()) {
            if (CommandMenuState.isOpen()) {
                CommandMenuState.close();
                continue;
            }
            // Creative only. Survival, adventure and spectator get nothing —
            // closing above stays mode-independent so an already-open menu can
            // always be dismissed.
            if (mc.player == null || !mc.player.isCreative()) continue;
            tryOpen(mc);
        }

        if (CommandMenuState.isOpen()) {
            CommandMenuState.onClientTick();
            // In screen-space, hover is resolved from the cursor in CommandMenuGuiScreen#render.
            // In world-space there is no cursor, so the camera ray has to be re-run every tick,
            // and the crosshair target kept clobbered so the block behind the panel can't be
            // mined or placed against through it.
            if (CommandMenuState.isOpenWorldspace()) {
                CommandMenuRaycast.updateHovered();
                if (mc.player != null) {
                    mc.hitResult = BlockHitResult.miss(
                        mc.player.getEyePosition(),
                        Direction.UP,
                        mc.player.blockPosition()
                    );
                }
            }
            // stopDestroyBlock halts destroy progress accumulated before the menu opened.
            if (CommandMenuState.isOpen() && mc.gameMode != null) {
                mc.gameMode.stopDestroyBlock();
            }
        }
    }

    private static void tryOpen(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return;
        CommandMenuState.open();
    }
}
