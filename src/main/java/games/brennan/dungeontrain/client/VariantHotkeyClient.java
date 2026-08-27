package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuScreen;
import games.brennan.dungeontrain.net.BlockVariantMenuTogglePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VariantHotkeyPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

/**
 * Client-side keymap + tick watcher for the "variant place" key (default
 * {@code Z}; rebindable from the vanilla Controls menu).
 *
 * <p>Two behaviours coexist on this single key:
 * <ul>
 *   <li><b>Hold</b> — held flag is sent to the server via
 *       {@link VariantHotkeyPacket}; the existing
 *       {@code VariantBlockInteractions} flow gates "right-click adds the
 *       held block as a variant" on this flag. Unchanged from PR #64.</li>
 *   <li><b>Tap</b> — short press without an interaction in between sends
 *       a {@link BlockVariantMenuTogglePacket} to open (or close, when the
 *       menu is already up) the world-space block-variant menu.</li>
 * </ul>
 * Tap detection: a press is considered a tap if it was released within
 * {@link #TAP_THRESHOLD_TICKS} ticks AND no use-interaction fired during
 * the press. Disambiguates "I tapped Z to open the menu" from "I held Z
 * and right-clicked to add a block."</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VariantHotkeyClient {

    public static final String CATEGORY = "key.categories." + DungeonTrain.MOD_ID;
    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".variant_place";

    /** A press shorter than this in client ticks counts as a tap (8 ticks ≈ 400ms). */
    private static final long TAP_THRESHOLD_TICKS = 8;

    /**
     * Package-visible via {@link #key()} so the screen-space panel can close on a second press.
     * Vanilla stops polling keybindings while a Screen is up, so the tick watcher below can never
     * see that press — the screen has to match the binding itself.
     */
    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_Z,
        CATEGORY
    );

    private static boolean lastSentHeld = false;

    /** Tick counter at the moment of the most recent press transition. -1 = no press in flight. */
    private static long pressStartTick = -1;
    /** True when a use-interaction (right-click) fired between press and release — disqualifies the tap. */
    private static boolean useDuringPress = false;

    private VariantHotkeyClient() {}

    /** The toggle binding, for screens that must match it directly. See {@link #KEY}. */
    public static KeyMapping key() {
        return KEY;
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    public static boolean isKeyDown() {
        return KEY.isDown();
    }

    /**
     * Forge bus listener — subscribed via the FORGE bus separately so it ticks
     * during the client game loop (not just during mod init).
     */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class TickWatcher {
        private static long tick;

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            tick++;
            if (Minecraft.getInstance().getConnection() == null
                    || TemplateBlocksHotkeyClient.inSurvival()
                    || !EditorStatusHudOverlay.isActive()) {
                if (lastSentHeld) {
                    // Walked out of an editor plot mid-hold — release on the
                    // server so right-click-to-add doesn't stay armed.
                    DungeonTrainNet.sendToServer(new VariantHotkeyPacket(false));
                    lastSentHeld = false;
                }
                pressStartTick = -1;
                useDuringPress = false;
                return;
            }
            // Ctrl+Z is the editor's undo (EditorUndoHotkeyClient). A plain-Z
            // mapping stays "active" while Ctrl is held — KeyModifier.NONE only
            // suppresses itself outside IN_GAME contexts — so without this
            // every undo would also arm right-click-to-add for the length of
            // the press. Ctrl is not a build or movement modifier in-game, so
            // nothing is lost by ignoring Z while it is down.
            if (Screen.hasControlDown()) {
                if (lastSentHeld) {
                    DungeonTrainNet.sendToServer(new VariantHotkeyPacket(false));
                    lastSentHeld = false;
                }
                pressStartTick = -1;
                useDuringPress = false;
                return;
            }

            // The screen-space panel matches this binding itself, in keyPressed — it has to,
            // because vanilla stops polling keybindings while a Screen is up. Standing down here
            // keeps that the only place a press is counted: if MC does keep the mapping updated
            // behind a screen, both would fire and the second toggle would undo the first.
            if (Minecraft.getInstance().screen instanceof BlockVariantMenuScreen) return;

            boolean held = KEY.isDown();
            if (held == lastSentHeld) return;

            // Send the held-state packet so the server-side
            // VariantHotkeyState (used by the existing right-click-to-add
            // flow) tracks this player's key state.
            DungeonTrainNet.sendToServer(new VariantHotkeyPacket(held));
            lastSentHeld = held;

            if (held) {
                pressStartTick = tick;
                useDuringPress = false;
            } else {
                // Release transition — was this a tap?
                if (pressStartTick >= 0
                    && tick - pressStartTick < TAP_THRESHOLD_TICKS
                    && !useDuringPress) {
                    boolean opening = !BlockVariantMenu.isActive();
                    DungeonTrainNet.sendToServer(new BlockVariantMenuTogglePacket(opening));
                }
                pressStartTick = -1;
                useDuringPress = false;
            }
        }

        /**
         * Detect right-clicks (or any use-interaction) that fire while Z
         * is held — disqualifies the press from being treated as a menu
         * tap. Mirrors the disambiguation pattern in the part-menu input
         * handler.
         */
        @SubscribeEvent
        public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
            if (KEY.isDown() && pressStartTick >= 0) {
                useDuringPress = true;
            }
        }
    }
}
