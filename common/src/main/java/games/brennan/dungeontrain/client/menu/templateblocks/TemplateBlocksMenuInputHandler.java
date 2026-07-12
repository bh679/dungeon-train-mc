package games.brennan.dungeontrain.client.menu.templateblocks;

import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.net.platform.DtNetSender;
import games.brennan.dungeontrain.net.TemplateBlocksEditPacket;
import games.brennan.dungeontrain.net.TemplateBlocksMenuTogglePacket;
import games.brennan.dungeontrain.net.TemplateBlocksSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Mouse + keyboard wiring for the template-blocks world-space menu. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler}
 * but with three actions only: close, preview a row, and swap a row.
 *
 * <p>Left-click release on a hovered cell dispatches; while the panel is up
 * and the click lands on a cell, attack / use interactions are cancelled so
 * the player doesn't break or place blocks through the panel.</p>
 */
public final class TemplateBlocksMenuInputHandler {

    private static boolean pressArmed;

    private TemplateBlocksMenuInputHandler() {}

    public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
        if (!shouldHandle()) return;
        TemplateBlocksMenu.Hit hit = TemplateBlocksMenu.hovered();
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return;
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

        TemplateBlocksMenu.Hit hit = TemplateBlocksMenu.hovered();
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return false;
        dispatch(hit);
        return false;
    }

    public static boolean onLeftClickBlock(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!shouldHandle()) return false;
        TemplateBlocksMenu.Hit hit = TemplateBlocksMenu.hovered();
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return false;
        return true;
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!TemplateBlocksMenu.isActive()) return;
        if (mc.screen == null) {
            TemplateBlocksMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!TemplateBlocksMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    private static void dispatch(TemplateBlocksMenu.Hit hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        String key = TemplateBlocksMenu.key();
        List<TemplateBlocksSyncPacket.Entry> entries = TemplateBlocksMenu.entries();
        switch (hit.kind()) {
            case CLOSE -> DtNetSender.get().sendToServer(new TemplateBlocksMenuTogglePacket(false));
            case ROW -> {
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                DtNetSender.get().sendToServer(new TemplateBlocksEditPacket(
                    TemplateBlocksEditPacket.Op.PREVIEW_BLOCK, key, entries.get(hit.index()).blockId()));
            }
            case ROW_SWAP -> {
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                DtNetSender.get().sendToServer(new TemplateBlocksEditPacket(
                    TemplateBlocksEditPacket.Op.SWAP_BLOCK, key, entries.get(hit.index()).blockId()));
            }
            default -> {}
        }
    }
}
