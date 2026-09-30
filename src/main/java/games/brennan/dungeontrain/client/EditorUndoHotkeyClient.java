package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenActions;
import games.brennan.dungeontrain.editor.PlotCategory;
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
 * Ctrl+Z / Ctrl+Y (⌘Z / ⌘Y on macOS) for the in-world editor, and Ctrl+R (⌘R)
 * to test the template the author is standing in — or, inside a test, to re-roll it.
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
 *
 * <p>Ctrl+R is the exception to "works from anywhere". Inside a test
 * ({@link PortalTestSessionState#active()}) it runs the same
 * {@link EditorScreenActions#RESEED_NOW_COMMAND} as the X menu's Reseed button. Outside a
 * test it is Test the Carriage for the plot the author is standing in, through the same
 * save check as the menus — straight in when clean, a save prompt when not. It never
 * touches the world's reseed switch (the Reseed button outside a test): a shortcut that
 * silently flipped a setting would be a trap.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorUndoHotkeyClient {

    public static final String CATEGORY = "key.categories." + DungeonTrain.MOD_ID;
    public static final String UNDO_NAME = "key." + DungeonTrain.MOD_ID + ".editor_undo";
    public static final String REDO_NAME = "key." + DungeonTrain.MOD_ID + ".editor_redo";
    public static final String RESEED_NAME = "key." + DungeonTrain.MOD_ID + ".editor_reseed";

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

    private static final KeyMapping RESEED = new KeyMapping(
        RESEED_NAME,
        KeyConflictContext.IN_GAME,
        KeyModifier.CONTROL,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_R,
        CATEGORY
    );

    private EditorUndoHotkeyClient() {}

    /**
     * The undo or redo command for a key pressed inside an editor screen, or null for any other
     * key. The bindings are {@link KeyConflictContext#IN_GAME} and the tick watcher stands down
     * while a screen is open, so a screen that wants the shortcut asks here — same binding, same
     * modifier, same command, rebinding included.
     */
    public static String commandForScreenKey(int keyCode, int scanCode) {
        if (TemplateBlocksHotkeyClient.inSurvival()) return null;
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        if (pressed(UNDO, key)) return "dungeontrain editor undo";
        if (pressed(REDO, key)) return "dungeontrain editor redo";
        return null;
    }

    /**
     * True when a key pressed inside an editor screen is the test / reseed binding — the screen
     * then runs its own Test (or, inside a test, Reseed) button, mirroring the in-world key.
     */
    public static boolean isTestKey(int keyCode, int scanCode) {
        if (TemplateBlocksHotkeyClient.inSurvival()) return false;
        return pressed(RESEED, InputConstants.getKey(keyCode, scanCode));
    }

    /**
     * Test the plot the author is standing in — the row-list menu's Test the Carriage row, read
     * from the same HUD state. The save check it opens goes straight in when the plot is clean
     * and asks to save first when it isn't. Outside a plot, or in one with nothing to test (a
     * part), the key does nothing.
     */
    private static void enterTestHere() {
        if (!EditorStatusHudOverlay.isActive()) return;
        PlotCategory category = PlotCategory.fromId(EditorStatusHudOverlay.category()).orElse(null);
        MenuScreen check = EditorScreenActions.testCheckFor(category,
            EditorStatusHudOverlay.modelId(), EditorStatusHudOverlay.modelName());
        if (check != null) CommandMenuState.openAt(check);
    }

    private static boolean pressed(KeyMapping mapping, InputConstants.Key key) {
        return !mapping.isUnbound() && mapping.getKey().equals(key)
            && mapping.getKeyModifier().isActive(KeyConflictContext.GUI);
    }

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(UNDO);
        event.register(REDO);
        event.register(RESEED);
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
            while (RESEED.consumeClick()) {
                if (PortalTestSessionState.active()) {
                    CommandRunner.run(EditorScreenActions.reseedNowCommand());
                } else {
                    enterTestHere();
                }
            }
        }
    }
}
