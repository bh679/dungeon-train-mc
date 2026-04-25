package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.net.BlockVariantEditPacket;
import games.brennan.dungeontrain.net.BlockVariantMenuTogglePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Mouse + keyboard wiring for the block-variant world-space menu.
 * Mirrors {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler}
 * with the new toolbar (Copy added) and the per-row Lock cell (replacing
 * side-mode cycle).
 *
 * <ul>
 *   <li>Left-click <i>release</i> on a hovered cell dispatches the
 *       appropriate action — toolbar buttons, weight bumps (with shift
 *       for decrement), lock toggle, entry-removal X, and search-result
 *       clicks.</li>
 *   <li>While the panel is active and the click is on a cell, attack /
 *       use interactions are cancelled so the player doesn't accidentally
 *       break / place blocks while clicking the panel.</li>
 *   <li>Defers to the keyboard-opened {@link CommandMenuState} when both
 *       are open (matches part-menu behaviour).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class BlockVariantMenuInputHandler {

    private static boolean pressArmed;
    private static boolean pressShift;

    private BlockVariantMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
        pressShift = Minecraft.getInstance().screen == null
            && (GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
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
        int btn = event.getButton();
        if (btn != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (event.getAction() != GLFW.GLFW_RELEASE) return;
        if (!pressArmed) return;
        pressArmed = false;
        boolean shift = pressShift
            || (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;

        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        dispatch(hit, shift);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BlockVariantSearchScreen
            && (!BlockVariantMenu.isActive()
                || BlockVariantMenu.screen() != BlockVariantMenu.Screen.ADD_SEARCH)) {
            mc.setScreen(null);
        }
        if (!BlockVariantMenu.isActive()) return;
        if (mc.screen == null) {
            BlockVariantMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!BlockVariantMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    private static void dispatch(BlockVariantMenu.Hit hit, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        if (BlockVariantMenu.screen() == BlockVariantMenu.Screen.ROOT) {
            dispatchRoot(hit, shift);
        } else {
            dispatchSearch(hit);
        }
    }

    private static void dispatchRoot(BlockVariantMenu.Hit hit, boolean shift) {
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        if (local == null) return;
        String variantId = BlockVariantMenu.variantId();
        switch (hit.kind()) {
            case COPY -> DungeonTrainNet.CHANNEL.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.COPY, variantId, local, -1, "", 0));
            case ADD -> BlockVariantMenu.enterSearch();
            case REMOVE -> BlockVariantMenu.toggleRemoveMode();
            case CLEAR -> DungeonTrainNet.CHANNEL.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CLEAR, variantId, local, -1, "", 0));
            case CLOSE -> DungeonTrainNet.CHANNEL.sendToServer(new BlockVariantMenuTogglePacket(false));
            case ENTRY_WEIGHT -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                int delta = shift ? -1 : 1;
                DungeonTrainNet.CHANNEL.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.BUMP_WEIGHT, variantId, local, hit.index(), "", delta));
            }
            case ENTRY_LOCK -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                DungeonTrainNet.CHANNEL.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.TOGGLE_LOCK, variantId, local, hit.index(), "", 0));
            }
            case ENTRY_REMOVE_X -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                DungeonTrainNet.CHANNEL.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.REMOVE, variantId, local, hit.index(), "", 0));
            }
            default -> {}
        }
    }

    private static void dispatchSearch(BlockVariantMenu.Hit hit) {
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        if (local == null) return;
        String variantId = BlockVariantMenu.variantId();
        switch (hit.kind()) {
            case SEARCH_BACK -> BlockVariantMenu.backToRoot();
            case SEARCH_FIELD -> Minecraft.getInstance().setScreen(new BlockVariantSearchScreen());
            case SEARCH_RESULT -> {
                List<String> filtered = BlockVariantMenu.filteredBlockIds();
                if (hit.index() < 0 || hit.index() >= filtered.size()) return;
                DungeonTrainNet.CHANNEL.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.ADD, variantId, local, -1, filtered.get(hit.index()), 0));
                BlockVariantMenu.backToRoot();
            }
            default -> {}
        }
    }
}
