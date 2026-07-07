package games.brennan.dungeontrain.client.menu.templateblocks;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlocksEditPacket;
import games.brennan.dungeontrain.net.TemplateBlocksMenuTogglePacket;
import games.brennan.dungeontrain.net.TemplateBlocksSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class TemplateBlocksMenuInputHandler {

    private static boolean pressArmed;

    private TemplateBlocksMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        TemplateBlocksMenu.Hit hit = TemplateBlocksMenu.hovered();
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return;
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

        TemplateBlocksMenu.Hit hit = TemplateBlocksMenu.hovered();
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return;
        dispatch(hit);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        TemplateBlocksMenu.Hit hit = TemplateBlocksMenu.hovered();
        if (hit.kind() == TemplateBlocksMenu.CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
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
            case CLOSE -> DungeonTrainNet.sendToServer(new TemplateBlocksMenuTogglePacket(false));
            case ROW -> {
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                DungeonTrainNet.sendToServer(new TemplateBlocksEditPacket(
                    TemplateBlocksEditPacket.Op.PREVIEW_BLOCK, key, entries.get(hit.index()).blockId()));
            }
            case ROW_SWAP -> {
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                DungeonTrainNet.sendToServer(new TemplateBlocksEditPacket(
                    TemplateBlocksEditPacket.Op.SWAP_BLOCK, key, entries.get(hit.index()).blockId()));
            }
            default -> {}
        }
    }
}
