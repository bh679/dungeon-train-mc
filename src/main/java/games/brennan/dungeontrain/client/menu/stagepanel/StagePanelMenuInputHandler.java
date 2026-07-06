package games.brennan.dungeontrain.client.menu.stagepanel;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.StageDuplicateNameScreen;
import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StagePanelEditPacket;
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

import java.util.List;

/**
 * Mouse wiring for the Stage Blocks panel — the single-panel copy of
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler}'s
 * press-arm / release-dispatch skeleton. Defers to the keyboard menu and the other world-space
 * menus (block-variant, part-position); panels are spatially disjoint billboards, so at most one
 * raycast reports a hover — the {@link EditorTypeMenuRenderer#hovered()} guard below is the
 * belt-and-braces against a future overlapping layout double-dispatching one click.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StagePanelMenuInputHandler {

    private static boolean pressArmed;

    private StagePanelMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        StagePanelMenu.Hit hit = StagePanelMenu.hovered();
        if (hit.kind() == StagePanelMenu.CellKind.NONE) return;
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

        StagePanelMenu.Hit hit = StagePanelMenu.hovered();
        if (hit.kind() == StagePanelMenu.CellKind.NONE) return;
        dispatch(hit);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        StagePanelMenu.Hit hit = StagePanelMenu.hovered();
        if (hit.kind() == StagePanelMenu.CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof StagePanelSearchScreen
            && (!StagePanelMenu.isActive()
                || StagePanelMenu.screen() != StagePanelMenu.Screen.REPLACE_SEARCH)) {
            mc.setScreen(null);
        }
        if (!StagePanelMenu.isActive()) return;
        if (mc.screen == null) {
            StagePanelMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!StagePanelMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        // Defer to the other world-space menus while they own the crosshair.
        if (BlockVariantMenu.isActive()) return false;
        if (PartPositionMenu.isActive()) return false;
        // Disjoint billboards ⇒ at most one non-NONE hover; guard anyway.
        if (EditorTypeMenuRenderer.hovered().cell() != EditorTypeMenuRenderer.CellKind.NONE) return false;
        return true;
    }

    private static void dispatch(StagePanelMenu.Hit hit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        String stageId = StagePanelMenu.stageId();
        switch (hit.kind()) {
            case CLOSE -> DungeonTrainNet.sendToServer(
                new StagePanelEditPacket(StagePanelEditPacket.Op.CLOSE, stageId, "", ""));
            case HIDE_TOGGLE -> DungeonTrainNet.sendToServer(
                new StagePanelEditPacket(StagePanelEditPacket.Op.TOGGLE_HIDE_UNUSED, stageId, "", ""));
            case DUPLICATE -> CommandMenuState.openAt(new StageDuplicateNameScreen(stageId));
            case BLOCK_CELL -> {
                List<String> blocks = StagePanelMenu.blocks();
                if (hit.index() >= 0 && hit.index() < blocks.size()) {
                    StagePanelMenu.enterReplaceSearch(blocks.get(hit.index()));
                }
            }
            case PART_BLOCK -> {
                var parts = StagePanelMenu.parts();
                if (hit.index() >= 0 && hit.index() < parts.size()) {
                    List<String> ids = parts.get(hit.index()).blockIds();
                    if (hit.secondary() >= 0 && hit.secondary() < ids.size()) {
                        StagePanelMenu.enterReplaceSearch(ids.get(hit.secondary()));
                    }
                }
            }
            case SEARCH_BACK -> StagePanelMenu.backToRoot();
            case SEARCH_FIELD -> mc.setScreen(new StagePanelSearchScreen());
            case SEARCH_RESULT -> {
                List<String> filtered = StagePanelMenu.filteredBlockIds();
                if (hit.index() >= 0 && hit.index() < filtered.size()) {
                    StagePanelMenu.chooseReplacement(filtered.get(hit.index()));
                }
            }
            case CONFIRM_YES -> {
                DungeonTrainNet.sendToServer(new StagePanelEditPacket(
                    StagePanelEditPacket.Op.REPLACE_BLOCK, stageId,
                    StagePanelMenu.replaceFrom(), StagePanelMenu.replaceTo()));
                StagePanelMenu.backToRoot();
            }
            case CONFIRM_NO -> StagePanelMenu.backToRoot();
            default -> { }
        }
    }
}
