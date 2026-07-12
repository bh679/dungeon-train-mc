package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.net.BlockVariantEditPacket;
import games.brennan.dungeontrain.net.BlockVariantMenuTogglePacket;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
public final class BlockVariantMenuInputHandler {

    private static boolean pressArmed;
    private static boolean pressShift;

    private BlockVariantMenuInputHandler() {}

    public static void onInteraction(games.brennan.dungeontrain.platform.event.DtInteractionInput input) {
        if (!shouldHandle()) return;
        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
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

        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return false;
        dispatch(hit, shift);
        return false;
    }

    public static boolean onLeftClickBlock(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!shouldHandle()) return false;
        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return false;
        return true;
    }

    public static void onClientTick() {
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
            case COPY -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.COPY, variantId, local, -1, "", 0));
            case SAVE -> {
                Minecraft.getInstance().setScreen(
                    new games.brennan.dungeontrain.client.menu.PrefabNameScreen(
                        games.brennan.dungeontrain.client.menu.PrefabNameScreen.Kind.VARIANT,
                        local));
            }
            case ADD -> DungeonTrainNet.sendToServer(
                // Server captures the player's main-hand BlockItem — empty
                // stateString signals "use held item" rather than the legacy
                // search-screen path.
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.ADD, variantId, local, -1, "", 0));
            case LOCK -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CYCLE_LOCK_ID, variantId, local, -1, "", 0));
            case REMOVE -> BlockVariantMenu.toggleRemoveMode();
            case CLEAR -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CLEAR, variantId, local, -1, "", 0));
            case CLOSE -> DungeonTrainNet.sendToServer(new BlockVariantMenuTogglePacket(false));
            case ENTRY_WEIGHT -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.BUMP_WEIGHT, variantId, local, hit.index(), "", delta));
            }
            case ENTRY_DIFF_MIN -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.BUMP_DIFF_MIN, variantId, local, hit.index(), "", delta));
            }
            case ENTRY_DIFF_MAX -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                int delta = shift ? -1 : 1;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.BUMP_DIFF_MAX, variantId, local, hit.index(), "", delta));
            }
            case ENTRY_REMOVE_X -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.REMOVE, variantId, local, hit.index(), "", 0));
            }
            case ENTRY_NAME -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                // A linked variant row routes to the container menu so the
                // user can edit the loot template directly. Unlinked rows
                // keep the original preview-in-world behaviour.
                BlockVariantEditPacket.Op op = e.linkedLootPrefabId() != null
                    ? BlockVariantEditPacket.Op.OPEN_LINKED_CONTAINER
                    : BlockVariantEditPacket.Op.PREVIEW_ENTRY;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    op, variantId, local, hit.index(), "", 0));
            }
            case ENTRY_ROT_MODE -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                int currentOrd = e.rotMode() & 0xFF;
                if (currentOrd >= VariantRotation.Mode.values().length) currentOrd = VariantRotation.Mode.RANDOM.ordinal();
                int nextOrd = (currentOrd + 1) % VariantRotation.Mode.values().length;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_ROTATION_MODE, variantId, local, hit.index(), "", nextOrd));
                BlockVariantMenu.closeRotPopup();
            }
            case ENTRY_HALF_MODE -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                int currentOrd = e.halfMode() & 0xFF;
                int modeCount = games.brennan.dungeontrain.editor.VariantHalf.Mode.values().length;
                if (currentOrd >= modeCount) {
                    currentOrd = games.brennan.dungeontrain.editor.VariantHalf.Mode.RANDOM.ordinal();
                }
                int nextOrd = (currentOrd + 1) % modeCount;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_HALF_MODE, variantId, local, hit.index(), "", nextOrd));
            }
            case ENTRY_ROT_DIRS -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                VariantRotation.Mode mode = decodeMode(e.rotMode());
                BlockState parsed = BlockVariantMenu.parseState(e.stateString());
                if (parsed == null) return;
                int validMask = RotationApplier.validDirMask(parsed);
                int currentMask = e.rotDirMask() & VariantRotation.ALL_DIRS_MASK;
                if (mode == VariantRotation.Mode.LOCK) {
                    int nextMask = nextLockBit(currentMask, validMask);
                    if (nextMask == 0) return;
                    DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                        BlockVariantEditPacket.Op.SET_ROTATION_DIRS, variantId, local, hit.index(), "", nextMask));
                } else if (mode == VariantRotation.Mode.OPTIONS) {
                    BlockVariantMenu.openRotPopup(hit.index());
                }
                // RANDOM mode: dir cell is read-only (no per-direction concept).
            }
            case ROT_DIR_OPTION -> {
                int row = hit.index();
                int dirOrd = hit.secondary();
                // Sentinel -2: click landed inside the menu panel but outside
                // the popup — close the popup and absorb the click.
                if (dirOrd == -2) {
                    BlockVariantMenu.closeRotPopup();
                    return;
                }
                if (row < 0 || row >= BlockVariantMenu.entries().size()) {
                    BlockVariantMenu.closeRotPopup();
                    return;
                }
                if (dirOrd < 0 || dirOrd >= 6) return; // backdrop click — ignore (popup stays open)
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(row);
                BlockState parsed = BlockVariantMenu.parseState(e.stateString());
                if (parsed == null) return;
                int validMask = RotationApplier.validDirMask(parsed);
                int bit = 1 << dirOrd;
                if ((validMask & bit) == 0) return; // invalid for this block — no-op
                int currentMask = e.rotDirMask() & VariantRotation.ALL_DIRS_MASK;
                int newMask = currentMask ^ bit;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_ROTATION_DIRS, variantId, local, row, "", newMask));
            }
            default -> {
                // Click outside any cell or on a non-popup cell while popup is open — close popup.
                if (BlockVariantMenu.rotPopupRowIndex() >= 0) {
                    BlockVariantMenu.closeRotPopup();
                }
            }
        }
    }

    private static VariantRotation.Mode decodeMode(byte raw) {
        int ord = raw & 0xFF;
        VariantRotation.Mode[] values = VariantRotation.Mode.values();
        if (ord < 0 || ord >= values.length) return VariantRotation.Mode.RANDOM;
        return values[ord];
    }

    /**
     * Cycle to the next valid bit for LOCK mode. Walks Direction.ordinal()
     * starting after the currently-set bit so repeated clicks move through
     * the property's allowed directions in order. Wraps around.
     */
    private static int nextLockBit(int currentMask, int validMask) {
        if (validMask == 0) return 0;
        int currentOrd = currentMask == 0 ? -1 : Integer.numberOfTrailingZeros(currentMask);
        for (int step = 1; step <= 6; step++) {
            int probe = (currentOrd + step) % 6;
            int bit = 1 << probe;
            if ((validMask & bit) != 0) return bit;
        }
        return Integer.lowestOneBit(validMask);
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
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.ADD, variantId, local, -1, filtered.get(hit.index()), 0));
                BlockVariantMenu.backToRoot();
            }
            default -> {}
        }
    }
}
