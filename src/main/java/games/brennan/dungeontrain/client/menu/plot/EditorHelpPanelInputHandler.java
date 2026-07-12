package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
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
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.net.URI;

/**
 * Mouse wiring for the worldspace editor help panel. Press-arm at
 * attack-cancel time, release dispatches the wiki link. Defers when the
 * keyboard menu or part menu owns input.
 */
public final class EditorHelpPanelInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String WIKI_URL = "https://github.com/bh679/dungeon-train-mc/wiki/Editor";

    private static boolean pressArmed;

    private EditorHelpPanelInputHandler() {}

    public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
        if (!shouldHandle()) return;
        Hovered hit = EditorHelpPanelRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
        input.setCanceled(true);
        input.setSwingHand(false);
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

    public static boolean onMouseButton(int button, int action, int modifiers) {
        if (!shouldHandle()) {
            pressArmed = false;
            return false;
        }
        if (Minecraft.getInstance().screen != null) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_RELEASE) return false;
        if (!pressArmed) return false;
        pressArmed = false;

        Hovered hit = EditorHelpPanelRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return false;
        dispatch(hit);
        return false;
    }

    public static boolean onLeftClickBlock(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!shouldHandle()) return false;
        if (EditorHelpPanelRenderer.hovered().cell() == CellKind.NONE) return false;
        return true;
    }

    public static void onClientTick() {
        if (!shouldHandle()) return;
        if (Minecraft.getInstance().screen == null) {
            EditorHelpPanelRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (EditorHelpPanelRenderer.firstNavMenu() == null) return false;
        if (CommandMenuState.isOpen()) return false;
        if (PartPositionMenu.isActive()) return false;
        return true;
    }

    private static void dispatch(Hovered hit) {
        if (hit.cell() != CellKind.WIKI_BUTTON) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        LOGGER.debug("[DungeonTrain] EditorHelpPanel wiki button clicked");
        mc.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(URI.create(WIKI_URL));
            }
            mc.setScreen(null);
        }, WIKI_URL, true));
    }
}
