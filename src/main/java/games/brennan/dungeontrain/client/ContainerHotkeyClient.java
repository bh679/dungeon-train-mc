package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderBoundsState;
import games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenu;
import games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenuScreen;
import games.brennan.dungeontrain.net.ContainerContentsMenuTogglePacket;
import games.brennan.dungeontrain.net.ContainerHotkeyPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

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
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ContainerHotkeyClient {

    public static final String CATEGORY = VariantHotkeyClient.CATEGORY;
    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".container_contents";

    private static final long TAP_THRESHOLD_TICKS = 8;

    /**
     * Package-visible via {@link #key()} so the screen-space panel can close on a second press.
     * Vanilla stops polling keybindings while a Screen is up, so {@code KeyMapping.isDown} in the
     * tick watcher below can never see that press — the screen has to match the binding itself.
     */
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

    /** The toggle binding, for screens that must match it directly. See {@link #KEY}. */
    public static KeyMapping key() {
        return KEY;
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    public static boolean isKeyDown() { return KEY.isDown(); }

    /**
     * Where this key does anything: inside an editor plot, or inside a Train Builder world.
     *
     * <p>Two questions rather than one because the two authoring worlds report themselves
     * differently — the editor pushes a status HUD from a per-player sweep gated on plot height,
     * which is why it stays silent at builder altitude, and the builder pushes its bounds. The
     * server side needs neither: {@code BlockVariantPlot.resolveAt} has answered for both since the
     * builder got its own plot.</p>
     */
    private static boolean authoring() {
        if (EditorStatusHudOverlay.isActive() || BuilderBoundsState.isInBuilderWorld()) return true;
        // Standing between plots in the editor world, or above the plot grid in a world that has one
        // stamped into it: the menus resolve from the block being looked at, so being off a plot is
        // no longer a reason not to send the press. The server still refuses if what you are looking
        // at isn't in one.
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
            && (games.brennan.dungeontrain.editor.EditorWorldLayout.isEditorWorld(mc.level)
                || games.brennan.dungeontrain.editor.EditorLayout.isAtPlotHeight(mc.player.getBlockY()));
    }

    /**
     * Inner class name MUST NOT clash with {@link VariantHotkeyClient.TickWatcher}.
     * Forge's @EventBusSubscriber registration appears to silently dedupe
     * inner static classes by simple name within a mod, so two classes both
     * named {@code TickWatcher} (even in different outer classes) cause one
     * of them to never receive events. Keep this class named distinctly.
     */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class ContainerTickWatcher {
        private static long tick;

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            tick++;
            if (Minecraft.getInstance().getConnection() == null
                    || TemplateBlocksHotkeyClient.inSurvival()
                    || !authoring()) {
                if (lastSentHeld) {
                    DungeonTrainNet.sendToServer(new ContainerHotkeyPacket(false));
                    lastSentHeld = false;
                }
                pressStartTick = -1;
                useDuringPress = false;
                return;
            }
            // The screen-space panel matches this binding itself, in keyPressed — it has to,
            // because vanilla stops polling keybindings while a Screen is up. Staying out of the
            // way here keeps that the only place a press is counted: if MC does keep the mapping
            // updated behind a screen, both would fire and the second toggle would undo the first.
            if (Minecraft.getInstance().screen instanceof ContainerContentsMenuScreen) return;

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

        @SubscribeEvent
        public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
            if (KEY.isDown() && pressStartTick >= 0) {
                useDuringPress = true;
            }
        }
    }
}
