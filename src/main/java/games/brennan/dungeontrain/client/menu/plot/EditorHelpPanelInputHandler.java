package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.plot.EditorHelpPanelRenderer.Hovered;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;
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

    private static boolean pressArmed;

    private EditorHelpPanelInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        Hovered hit = EditorHelpPanelRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.hitResult = BlockHitResult.miss(
                mc.player.getEyePosition(),
                Direction.UP,
                mc.player.blockPosition()
            );
        }
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!shouldHandle()) {
            pressArmed = false;
            return;
        }
        if (Minecraft.getInstance().screen != null) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (event.getAction() != GLFW.GLFW_RELEASE) return;
        if (!pressArmed) return;
        pressArmed = false;

        Hovered hit = EditorHelpPanelRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
        dispatch(hit);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        if (EditorHelpPanelRenderer.hovered().cell() == CellKind.NONE) return;
        event.setCanceled(true);
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
        if (PartPositionMenu.isActive()) return false;
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
            case NONE -> { }
        }
    }

    private static void click(Minecraft mc) {
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }
}
