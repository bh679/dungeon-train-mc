package games.brennan.dungeontrain.client;
import games.brennan.dungeontrain.DtCore;

import com.mojang.blaze3d.platform.InputConstants;

import games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenu;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlocksMenuTogglePacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Client-side keymap for opening the template-blocks world-space menu.
 *
 * <p>Defaults to V — rebindable from the vanilla Controls menu, or use the
 * alternate trigger {@code /dungeontrain editor blocks}. A press toggles the
 * menu open/closed via {@link TemplateBlocksMenuTogglePacket}; the server
 * validates that the player is OP and standing in an editor plot.</p>
 */
public final class TemplateBlocksHotkeyClient {

    public static final String CATEGORY = "key.categories." + DtCore.MOD_ID;
    public static final String NAME = "key." + DtCore.MOD_ID + ".template_blocks";

    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_V,
        CATEGORY
    );

    private TemplateBlocksHotkeyClient() {}

        public static void onRegister(java.util.function.Consumer<net.minecraft.client.KeyMapping> registrar) {
        registrar.accept(KEY);
    }

    /** Forge-bus listener — ticks during the client game loop to catch the key press edge. */
    public static final class TickWatcher {
        private static boolean lastDown;

        public static void onClientTick() {
            if (Minecraft.getInstance().getConnection() == null) {
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
