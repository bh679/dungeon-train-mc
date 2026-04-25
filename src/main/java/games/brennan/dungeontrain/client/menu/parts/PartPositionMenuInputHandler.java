package games.brennan.dungeontrain.client.menu.parts;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PartAssignmentEditPacket;
import games.brennan.dungeontrain.net.PartMenuTogglePacket;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import games.brennan.dungeontrain.train.CarriagePartKind;
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
 * Mouse + keyboard wiring for the auto-opening part-position menu.
 *
 * <ul>
 *   <li>Left-click <i>release</i> on a hovered cell dispatches the
 *       appropriate action — toolbar buttons, weight bumps (with shift
 *       for decrement), entry-removal X, and search-result clicks.</li>
 *   <li>While the panel is active the mouse-attack and use interactions
 *       are cancelled so the player doesn't accidentally break the
 *       carriage block they're aiming at.</li>
 *   <li>The panel co-exists with the keyboard-opened
 *       {@link CommandMenuState}: when the latter is open, clicks belong
 *       to it (it has its own handler), so this handler defers.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class PartPositionMenuInputHandler {

    /** True only when a press fired while the menu was active and we're awaiting its release. */
    private static boolean pressArmed;
    /** Captured at press time so the release knows whether it was a shift-click. */
    private static boolean pressShift;

    private PartPositionMenuInputHandler() {}

    /** Cancel the world-targeted attack/use when a press lands on the panel. Mirrors CommandMenuInputHandler. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        // Only cancel when the press is actually on a panel cell — otherwise
        // a player editing a carriage with the menu open should still be able
        // to break / place blocks they're not aiming at the panel for.
        PartPositionMenu.Hit hit = PartPositionMenu.hovered();
        if (hit.kind() == PartPositionMenu.CellKind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
        pressShift = Minecraft.getInstance().screen == null
            && (GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
        // Clobber hitResult so the next tick's continueAttack sees a miss —
        // prevents block-mining progress from accumulating during the click.
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

        PartPositionMenu.Hit hit = PartPositionMenu.hovered();
        if (hit.kind() == PartPositionMenu.CellKind.NONE) return;
        dispatch(hit, shift);
    }

    /**
     * Belt-and-braces guard: if a click somehow slips past
     * {@link InputEvent.InteractionKeyMappingTriggered}, cancel the
     * left-click-block event so block damage doesn't begin.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        PartPositionMenu.Hit hit = PartPositionMenu.hovered();
        if (hit.kind() == PartPositionMenu.CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        // Auto-dismiss the search typing screen if the underlying menu
        // is no longer on the ADD_SEARCH sub-screen — happens when the
        // server sync transitions kinds or closes the menu while the
        // player has the typing capture open.
        if (mc.screen instanceof PartMenuSearchScreen
            && (!PartPositionMenu.isActive()
                || PartPositionMenu.screen() != PartPositionMenu.Screen.ADD_SEARCH)) {
            mc.setScreen(null);
        }
        if (!PartPositionMenu.isActive()) return;
        // Refresh hover so the per-tick state matches the panel even
        // outside the per-frame render call (used by mouse-press arming).
        if (mc.screen == null) {
            PartPositionMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!PartPositionMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    private static void dispatch(PartPositionMenu.Hit hit, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        if (PartPositionMenu.screen() == PartPositionMenu.Screen.ROOT) {
            dispatchRoot(hit, shift);
        } else {
            dispatchSearch(hit);
        }
    }

    private static void dispatchRoot(PartPositionMenu.Hit hit, boolean shift) {
        CarriagePartKind kind = PartPositionMenu.kind();
        if (kind == null) return;
        String variantId = PartPositionMenu.variantId();
        switch (hit.kind()) {
            case ADD -> PartPositionMenu.enterSearch();
                // No auto-open of the typing screen — the player must click the
                // search field on the ADD_SEARCH sub-screen to start typing.
                // Keeps the click-release contract consistent: every action
                // (typing focus included) requires its own deliberate click.
            case REMOVE -> PartPositionMenu.toggleRemoveMode();
            case CLEAR -> DungeonTrainNet.CHANNEL.sendToServer(
                new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.CLEAR, variantId, kind, "", 0));
            case CLOSE -> DungeonTrainNet.CHANNEL.sendToServer(new PartMenuTogglePacket(false));
            case ENTRY_WEIGHT -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                int delta = shift ? -1 : 1;
                DungeonTrainNet.CHANNEL.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.BUMP_WEIGHT, variantId, kind, name, delta));
            }
            case ENTRY_REMOVE_X -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                DungeonTrainNet.CHANNEL.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.REMOVE, variantId, kind, name, 0));
            }
            case ENTRY_SIDE_MODE -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                DungeonTrainNet.CHANNEL.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.CYCLE_SIDE_MODE, variantId, kind, name, 0));
            }
            default -> {}
        }
    }

    private static void dispatchSearch(PartPositionMenu.Hit hit) {
        CarriagePartKind kind = PartPositionMenu.kind();
        if (kind == null) return;
        String variantId = PartPositionMenu.variantId();
        switch (hit.kind()) {
            case SEARCH_BACK -> PartPositionMenu.backToRoot();
            case SEARCH_FIELD -> Minecraft.getInstance().setScreen(new PartMenuSearchScreen());
            case SEARCH_RESULT -> {
                List<String> filtered = PartPositionMenu.filteredRegisteredNames();
                if (hit.index() < 0 || hit.index() >= filtered.size()) return;
                DungeonTrainNet.CHANNEL.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.ADD, variantId, kind, filtered.get(hit.index()), 0));
                PartPositionMenu.backToRoot();
            }
            default -> {}
        }
    }
}
