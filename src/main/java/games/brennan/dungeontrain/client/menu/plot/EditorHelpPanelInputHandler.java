package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.WorldPanelInput;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.EditorPanelFacingEvents;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer.Hovered;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import java.net.URI;

/**
 * Mouse wiring for the worldspace editor help panel. Press-arm at
 * attack-cancel time, release dispatches the wiki link or the close button.
 * Defers when the keyboard menu or part menu owns input.
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorHelpPanelInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String WIKI_URL = "https://github.com/bh679/dungeon-train-mc/wiki/Editor";

    /**
     * Closing goes through the server, not a client flag: the dismissal is persisted per player in
     * the world save, so the server has to be the one to write it. The X-menu "Welcome Panel" row
     * runs the same command with {@code on}.
     */
    static final String CLOSE_COMMAND = "dungeontrain editor helppanel off";

    private static final WorldPanelInput<Hovered> INPUT = WorldPanelInput.leftOnly(
        EditorHelpPanelRenderer::hovered, hit -> hit.cell() == CellKind.NONE,
        EditorHelpPanelInputHandler::shouldHandle, EditorHelpPanelInputHandler::dispatch);

    private EditorHelpPanelInputHandler() {}

    /** The press: cancels the world interaction and arms when the crosshair is on a panel cell. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        INPUT.onInteraction(event);
    }

    /** The release of the armed button fires the hit under the crosshair. */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        INPUT.onMouseButton(event);
    }

    /** Belt and braces: a left-click-block that slipped past the press cancel must not start block damage. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        INPUT.onLeftClickBlock(event);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!shouldHandle()) return;
        if (Minecraft.getInstance().screen == null) {
            EditorHelpPanelRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!EditorStatusHudOverlay.isEditorMenusVisible()) return false;
        if (EditorTypeMenuRenderer.helpPanelDismissed()) return false;
        if (EditorHelpPanelRenderer.firstNavMenu() == null) return false;
        if (CommandMenuState.isOpen()) return false;
        if (PartPositionMenu.claimsPointer()) return false;
        if (games.brennan.dungeontrain.client.menu.creakingheart.CreakingHeartPanel.claimsPointer()) return false;
        return true;
    }

    private static void dispatch(Hovered hit) {
        Minecraft mc = Minecraft.getInstance();
        switch (hit.cell()) {
            case WIKI_BUTTON -> {
                click(mc);
                LOGGER.debug("[DungeonTrain] EditorHelpPanel wiki button clicked");
                mc.setScreen(new ConfirmLinkScreen(yes -> {
                    if (yes) {
                        Util.getPlatform().openUri(URI.create(WIKI_URL));
                    }
                    mc.setScreen(null);
                }, WIKI_URL, true));
            }
            case CLOSE_BUTTON -> {
                click(mc);
                LOGGER.debug("[DungeonTrain] EditorHelpPanel close button clicked");
                // The panel disappears when the server echoes the flag back on the next type-menus
                // snapshot — one tick, and no client-side guess to reconcile if the write fails.
                EditorHelpPanelRenderer.setHovered(Hovered.NONE);
                CommandRunner.run(CLOSE_COMMAND);
            }
            case FACE_BUTTON -> {
                click(mc);
                // Spins just the help panel about its own centre; the nav menu keeps its facing.
                EditorTypeMenusPacket.Menu nav = EditorHelpPanelRenderer.firstNavMenu();
                if (nav != null) {
                    EditorPanelFacingEvents.onButton(EditorHelpPanelRenderer.facingKey(nav),
                        EditorHelpPanelRenderer.helpAnchor(nav, mc.font),
                        EditorHelpPanelRenderer.gridDefault(nav));
                }
            }
            case NONE -> { }
        }
    }

    private static void click(Minecraft mc) {
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }
}
