package games.brennan.dungeontrain.client.menu.stagepalette;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.CellKind;
import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu.Hit;
import games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenu;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StagePaletteEditPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Mouse wiring for the Stage Palette panel — the same press-arm / release-dispatch skeleton as
 * {@link games.brennan.dungeontrain.client.menu.stagepanel.StagePanelMenuInputHandler}, and it
 * additionally yields to that panel's hover (the two billboards are disjoint, but guard anyway).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StagePaletteMenuInputHandler {

    private static boolean pressArmed;

    private StagePaletteMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        Hit hit = StagePaletteMenu.hovered();
        if (hit.kind() == CellKind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.hitResult = BlockHitResult.miss(mc.player.getEyePosition(), Direction.UP, mc.player.blockPosition());
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
        Hit hit = StagePaletteMenu.hovered();
        if (hit.kind() == CellKind.NONE) return;
        dispatch(hit);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        if (StagePaletteMenu.hovered().kind() == CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!EditorStatusHudOverlay.isEditorMenusVisible()) return;
        if (!StagePaletteMenu.isActive()) return;
        if (mc.screen == null) StagePaletteMenuRaycast.updateHovered();
    }

    private static boolean shouldHandle() {
        if (!EditorStatusHudOverlay.isEditorMenusVisible()) return false;
        if (!StagePaletteMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        if (BlockVariantMenu.isActive()) return false;
        if (PartPositionMenu.isActive()) return false;
        if (EditorTypeMenuRenderer.hovered().cell() != EditorTypeMenuRenderer.CellKind.NONE) return false;
        if (StagePanelMenu.hovered().kind() != StagePanelMenu.CellKind.NONE) return false;
        return true;
    }

    private static void dispatch(Hit hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        String stageId = StagePaletteMenu.stageId();
        switch (hit.kind()) {
            // Same open/close axis as the stage panel: X deselects the stage, closing both.
            case CLOSE -> games.brennan.dungeontrain.client.menu.CommandRunner.run("dungeontrain editor stage deselect");
            case REBAKE -> send(StagePaletteEditPacket.Op.REBAKE, stageId, "");
            case WOOD_HEADER -> send(StagePaletteEditPacket.Op.SET_WOOD, stageId, "");
            case STONE_HEADER -> send(StagePaletteEditPacket.Op.SET_STONE, stageId, "");
            case CELL -> {
                String name = StagePaletteMenu.cellName(hit);
                // The server reads the held block; an empty hand clears the override.
                if (name != null) send(StagePaletteEditPacket.Op.SET_OVERRIDE, stageId, name);
            }
            default -> { }
        }
    }

    private static void send(StagePaletteEditPacket.Op op, String stageId, String name) {
        DungeonTrainNet.sendToServer(new StagePaletteEditPacket(op, stageId, name));
    }
}
