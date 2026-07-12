package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenu;
import games.brennan.dungeontrain.net.ContainerContentsMenuTogglePacket;
import games.brennan.dungeontrain.net.ContainerHotkeyPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Client-side keymap for the "container contents" key (default {@code C};
 * rebindable from vanilla Controls). Mirrors {@link VariantHotkeyClient} with
 * a single behaviour: tap to open / close the world-space container-contents
 * menu when looking at a chest / barrel / dispenser etc. inside an editor
 * plot.
 *
 * <p>Held-state is also reported to the server (see
 * {@link games.brennan.dungeontrain.editor.ContainerHotkeyState}) for symmetry
 * with the Z key — currently unused but keeps the door open for future
 * "hold C and click" flows.</p>
 */
public final class ContainerHotkeyClient {

    public static final String CATEGORY = VariantHotkeyClient.CATEGORY;
    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".container_contents";

    private static final long TAP_THRESHOLD_TICKS = 8;

    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_C,
        CATEGORY
    );

    private static boolean lastSentHeld = false;
    private static long pressStartTick = -1;
    private static boolean useDuringPress = false;

    private ContainerHotkeyClient() {}

        public static void onRegister(java.util.function.Consumer<net.minecraft.client.KeyMapping> registrar) {
        registrar.accept(KEY);
    }

    public static boolean isKeyDown() { return KEY.isDown(); }

    /**
     * Inner class name MUST NOT clash with {@link VariantHotkeyClient.TickWatcher}.
     * Forge's @EventBusSubscriber registration appears to silently dedupe
     * inner static classes by simple name within a mod, so two classes both
     * named {@code TickWatcher} (even in different outer classes) cause one
     * of them to never receive events. Keep this class named distinctly.
     */
    public static final class ContainerTickWatcher {
        private static long tick;

        public static void onClientTick() {
            tick++;
            if (Minecraft.getInstance().getConnection() == null
                    || !EditorStatusHudOverlay.isActive()) {
                if (lastSentHeld) {
                    DungeonTrainNet.sendToServer(new ContainerHotkeyPacket(false));
                    lastSentHeld = false;
                }
                pressStartTick = -1;
                useDuringPress = false;
                return;
            }
            boolean held = KEY.isDown();
            if (held == lastSentHeld) return;

            DungeonTrainNet.sendToServer(new ContainerHotkeyPacket(held));
            lastSentHeld = held;

            if (held) {
                pressStartTick = tick;
                useDuringPress = false;
            } else {
                if (pressStartTick >= 0
                    && tick - pressStartTick < TAP_THRESHOLD_TICKS
                    && !useDuringPress) {
                    boolean opening = !ContainerContentsMenu.isActive();
                    DungeonTrainNet.sendToServer(new ContainerContentsMenuTogglePacket(opening));
                }
                pressStartTick = -1;
                useDuringPress = false;
            }
        }

        public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
            if (KEY.isDown() && pressStartTick >= 0) {
                useDuringPress = true;
            }
        }
    }
}
