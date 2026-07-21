package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenu;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlocksMenuTogglePacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Client-side keymap for opening the template-blocks world-space menu.
 *
 * <p>Defaults to V — rebindable from the vanilla Controls menu, or use the
 * alternate trigger {@code /dungeontrain editor blocks}. A press toggles the
 * menu open/closed via {@link TemplateBlocksMenuTogglePacket}; the server
 * validates that the player is OP and standing in an editor plot.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TemplateBlocksHotkeyClient {

    public static final String CATEGORY = "key.categories." + DungeonTrain.MOD_ID;
    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".template_blocks";

    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_V,
        CATEGORY
    );

    private TemplateBlocksHotkeyClient() {}

    /** True when the local player is in survival mode — editor hotkeys stay silent there. */
    static boolean inSurvival() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SURVIVAL;
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    /** Forge-bus listener — ticks during the client game loop to catch the key press edge. */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class TickWatcher {
        private static boolean lastDown;

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (Minecraft.getInstance().getConnection() == null || inSurvival()) {
                lastDown = false;
                return;
            }
            boolean down = KEY.isDown();
            if (down && !lastDown) {
                boolean opening = !TemplateBlocksMenu.isActive();
                DungeonTrainNet.sendToServer(new TemplateBlocksMenuTogglePacket(opening));
            }
            lastDown = down;
        }
    }
}
