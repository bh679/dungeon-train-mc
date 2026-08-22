package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

/**
 * Ctrl+Z / Ctrl+Y (⌘Z / ⌘Y on macOS) for the in-world editor.
 *
 * <p>{@link KeyModifier#CONTROL} is what makes one binding cover both
 * platforms: NeoForge resolves it to the Super keys under
 * {@code Minecraft.ON_OSX} and to the Control keys everywhere else, matching
 * what {@code Screen.hasControlDown()} reports. Authors on either platform get
 * the shortcut their muscle memory expects, and both stay rebindable from the
 * vanilla Controls screen.</p>
 *
 * <p>Dispatch goes through {@link CommandRunner} to
 * {@code /dungeontrain editor undo|redo} — the same server path the X menu's
 * Undo | Redo row uses, following {@link CinematographerHotkeyClient}. The
 * alternative, a bespoke packet, would have bought a second code path and a
 * protocol bump for nothing.</p>
 *
 * <p>Works from anywhere — the history is keyed per player, not per plot, so an
 * author can change a weight, walk away, and still take it back. Only survival
 * is gated out.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorUndoHotkeyClient {

    public static final String CATEGORY = "key.categories." + DungeonTrain.MOD_ID;
    public static final String UNDO_NAME = "key." + DungeonTrain.MOD_ID + ".editor_undo";
    public static final String REDO_NAME = "key." + DungeonTrain.MOD_ID + ".editor_redo";

    private static final KeyMapping UNDO = new KeyMapping(
        UNDO_NAME,
        KeyConflictContext.IN_GAME,
        KeyModifier.CONTROL,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_Z,
        CATEGORY
    );

    private static final KeyMapping REDO = new KeyMapping(
        REDO_NAME,
        KeyConflictContext.IN_GAME,
        KeyModifier.CONTROL,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_Y,
        CATEGORY
    );

    private EditorUndoHotkeyClient() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(UNDO);
        event.register(REDO);
    }

    /**
     * Forge-bus listener — separate subscriber so it ticks during the client
     * game loop, mirroring {@link VariantHotkeyClient.TickWatcher}.
     */
    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class TickWatcher {

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.screen != null) return;
            // Deliberately NOT gated on EditorStatusHudOverlay.isActive(): the
            // author may change something and walk off before realising it was
            // wrong, and the history is per-player rather than per-plot. The
            // survival gate stays — the editor is not a survival tool, and a
            // stray Ctrl+Z mid-run should do nothing.
            if (TemplateBlocksHotkeyClient.inSurvival()) return;

            // consumeClick drains one press per tick, so holding the key does
            // not run away with the history — one tap, one step.
            while (UNDO.consumeClick()) {
                CommandRunner.run("dungeontrain editor undo");
            }
            while (REDO.consumeClick()) {
                CommandRunner.run("dungeontrain editor redo");
            }
        }
    }
}
