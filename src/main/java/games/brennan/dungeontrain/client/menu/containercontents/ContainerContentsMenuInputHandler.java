package games.brennan.dungeontrain.client.menu.containercontents;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.net.ContainerContentsEditPacket;
import games.brennan.dungeontrain.net.ContainerContentsMenuTogglePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Mouse + keyboard wiring for {@link ContainerContentsMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler}
 * with simpler dispatch (no rotation, no lock).
 *
 * <ul>
 *   <li>Click on count or weight cell — server bumps by +1 (shift = -1).</li>
 *   <li>Click on the entry name — currently no-op (could preview later).</li>
 *   <li>Click on × cell — remove entry.</li>
 *   <li>Click ADD — open search screen.</li>
 *   <li>Click CLEAR — wipe pool.</li>
 *   <li>Click X (close) — close menu.</li>
 * </ul>
 */
public final class ContainerContentsMenuInputHandler {

    private static boolean pressArmed;
    private static boolean pressShift;

    private ContainerContentsMenuInputHandler() {}

    public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
        if (!shouldHandle()) return;
        ContainerContentsMenu.Hit hit = ContainerContentsMenu.hovered();
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return;
        input.setCanceled(true);
        input.setSwingHand(false);
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

    public static boolean onMouseButton(int button, int action, int modifiers) {
        if (!shouldHandle()) {
            pressArmed = false;
            return false;
        }
        if (Minecraft.getInstance().screen != null) return false;
        int btn = button;
        if (btn != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (action != GLFW.GLFW_RELEASE) return false;
        if (!pressArmed) return false;
        pressArmed = false;
        boolean shift = pressShift
            || (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        ContainerContentsMenu.Hit hit = ContainerContentsMenu.hovered();
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return false;
        dispatch(hit, shift);
        return false;
    }

    public static boolean onLeftClickBlock(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!shouldHandle()) return false;
        ContainerContentsMenu.Hit hit = ContainerContentsMenu.hovered();
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return false;
        return true;
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ContainerContentsSearchScreen
            && (!ContainerContentsMenu.isActive()
                || ContainerContentsMenu.screen() != ContainerContentsMenu.Screen.ADD_SEARCH)) {
            mc.setScreen(null);
        }
        if (!ContainerContentsMenu.isActive()) return;
        if (mc.screen == null) {
            ContainerContentsMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!ContainerContentsMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    private static void dispatch(ContainerContentsMenu.Hit hit, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        if (ContainerContentsMenu.screen() == ContainerContentsMenu.Screen.ROOT) {
            dispatchRoot(hit, shift);
        } else {
            dispatchSearch(hit);
        }
    }

    private static void dispatchRoot(ContainerContentsMenu.Hit hit, boolean shift) {
        net.minecraft.core.BlockPos local = ContainerContentsMenu.localPos();
        if (local == null) return;
        String plotKey = ContainerContentsMenu.plotKey();
        switch (hit.kind()) {
            case ADD -> DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                // Empty itemId signals "use main-hand item" — server captures
                // the held stack's item + count. Mirrors the block-variant
                // ADD behaviour: hold an item, click Add.
                ContainerContentsEditPacket.Op.ADD, plotKey, local, -1, "", 0));
            case SAVE -> {
                // Linked containers route every menu edit straight to the
                // template, so SAVE has no work to do — show a hint instead
                // of re-prompting for a name. First-time save (no link yet)
                // opens the name screen.
                String linked = ContainerContentsMenu.linkedPrefabId();
                if (linked != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                "Linked to '" + linked + "' — edits save automatically")
                                .withStyle(net.minecraft.ChatFormatting.AQUA),
                            true);
                    }
                } else {
                    Minecraft.getInstance().setScreen(
                        new games.brennan.dungeontrain.client.menu.PrefabNameScreen(
                            games.brennan.dungeontrain.client.menu.PrefabNameScreen.Kind.LOOT,
                            local));
                }
            }
            case FILL_MIN -> {
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.BUMP_FILL_MIN, plotKey, local, -1, "", delta));
            }
            case FILL_MAX -> {
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.BUMP_FILL_MAX, plotKey, local, -1, "", delta));
            }
            case CLEAR -> DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                ContainerContentsEditPacket.Op.CLEAR, plotKey, local, -1, "", 0));
            case CLOSE -> DungeonTrainNet.sendToServer(new ContainerContentsMenuTogglePacket(false));
            case ENTRY_COUNT_PLUS -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.BUMP_COUNT, plotKey, local, hit.index(), "", delta));
            }
            case ENTRY_WEIGHT_PLUS -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.BUMP_WEIGHT, plotKey, local, hit.index(), "", delta));
            }
            case ENTRY_REMOVE_X -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.REMOVE, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_RAND_DUR_TOGGLE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.TOGGLE_RAND_DUR, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_RAND_ENCH_TOGGLE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.TOGGLE_RAND_ENCH, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_DUR_CHANCE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                int delta = shift ? -5 : 5;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.BUMP_DUR_CHANCE, plotKey, local, hit.index(), "", delta));
            }
            case ENTRY_ENCH_CHANCE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                int delta = shift ? -5 : 5;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.BUMP_ENCH_CHANCE, plotKey, local, hit.index(), "", delta));
            }
            case ENTRY_SLOT_ASSIGN -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.CYCLE_SLOT_ASSIGN, plotKey, local, hit.index(), "", 0));
            }
            case LINK_UNLINK -> DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                ContainerContentsEditPacket.Op.UNLINK, plotKey, local, -1, "", 0));
            // LINK_INDICATOR is informational — no click action.
            default -> {}
        }
    }

    private static void dispatchSearch(ContainerContentsMenu.Hit hit) {
        net.minecraft.core.BlockPos local = ContainerContentsMenu.localPos();
        if (local == null) return;
        String plotKey = ContainerContentsMenu.plotKey();
        switch (hit.kind()) {
            case SEARCH_BACK -> ContainerContentsMenu.backToRoot();
            case SEARCH_FIELD -> Minecraft.getInstance().setScreen(new ContainerContentsSearchScreen());
            case SEARCH_RESULT -> {
                List<String> filtered = ContainerContentsMenu.filteredItemIds();
                if (hit.index() < 0 || hit.index() >= filtered.size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.ADD, plotKey, local, -1, filtered.get(hit.index()), 0));
                ContainerContentsMenu.backToRoot();
            }
            default -> {}
        }
    }
}
