package games.brennan.dungeontrain.client.menu.stagepanel;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.WorldPanelInput;
import games.brennan.dungeontrain.client.menu.StageDuplicateNameScreen;
import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StagePanelEditPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

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

    private static final WorldPanelInput<StagePanelMenu.Hit> INPUT = WorldPanelInput.leftOnly(
        StagePanelMenu::hovered, hit -> hit.kind() == StagePanelMenu.CellKind.NONE,
        StagePanelMenuInputHandler::shouldHandle, StagePanelMenuInputHandler::dispatch);

    private StagePanelMenuInputHandler() {}

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
        Minecraft mc = Minecraft.getInstance();
        if (!EditorStatusHudOverlay.isEditorMenusVisible()) return;
        if (!StagePanelMenu.isActive()) return;
        if (mc.screen == null) {
            StagePanelMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!EditorStatusHudOverlay.isEditorMenusVisible()) return false;
        if (!StagePanelMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        // Defer to the other world-space menus while they own the crosshair.
        if (BlockVariantMenu.isActive()) return false;
        if (PartPositionMenu.claimsPointer()) return false;
        if (games.brennan.dungeontrain.client.menu.creakingheart.CreakingHeartPanel.claimsPointer()) return false;
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
            // The panel exists only while a stage is selected, so the X closes it by DESELECTING the
            // stage (the single open/close axis) rather than an independent panel close.
            case CLOSE -> games.brennan.dungeontrain.client.menu.CommandRunner.run(
                "dungeontrain editor stage deselect");
            case HIDE_TOGGLE -> DungeonTrainNet.sendToServer(
                new StagePanelEditPacket(StagePanelEditPacket.Op.TOGGLE_HIDE_UNUSED, stageId, ""));
            case DUPLICATE -> CommandMenuState.openAt(new StageDuplicateNameScreen(stageId));
            case FACE -> {
                net.minecraft.core.BlockPos pos = StagePanelMenu.anchor();
                games.brennan.dungeontrain.client.menu.EditorPanelFacingEvents.onButton(pos,
                    net.minecraft.world.phys.Vec3.atCenterOf(pos), games.brennan.dungeontrain.client.menu.EditorPanelFacing.doorPanel(false));
            }
            case BLOCK_ROW -> {
                var blocks = StagePanelMenu.blocks();
                if (hit.index() >= 0 && hit.index() < blocks.size()) {
                    swap(stageId, blocks.get(hit.index()).blockId());
                }
            }
            case PART_BLOCK -> {
                var parts = StagePanelMenu.parts();
                if (hit.index() >= 0 && hit.index() < parts.size()) {
                    List<String> ids = parts.get(hit.index()).blockIds();
                    if (hit.secondary() >= 0 && hit.secondary() < ids.size()) {
                        swap(stageId, ids.get(hit.secondary()));
                    }
                }
            }
            default -> { }
        }
    }

    /** Send an immediate stage-wide swap of {@code blockId} with the player's held block (#636 style). */
    private static void swap(String stageId, String blockId) {
        DungeonTrainNet.sendToServer(
            new StagePanelEditPacket(StagePanelEditPacket.Op.SWAP_BLOCK, stageId, blockId));
    }
}
