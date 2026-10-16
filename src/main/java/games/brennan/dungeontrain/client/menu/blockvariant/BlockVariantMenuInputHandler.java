package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.number.NumberField;
import games.brennan.dungeontrain.client.menu.number.NumberPad;
import games.brennan.dungeontrain.client.menu.number.NumberStepper;
import games.brennan.dungeontrain.client.menu.number.NumberTitle;
import games.brennan.dungeontrain.editor.VariantDifficulty;
import games.brennan.dungeontrain.editor.VariantGrowth;
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
 * Mouse + keyboard wiring for the block-variant world-space menu.
 * Mirrors {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler}
 * with the new toolbar (Copy added) and the per-row Lock cell (replacing
 * side-mode cycle).
 *
 * <ul>
 *   <li>Left-click <i>release</i> on a hovered cell dispatches the
 *       appropriate action — toolbar buttons, lock toggle, entry-removal X,
 *       and search-result clicks. Number cells (weight, difficulty band, Grow
 *       lengths) go through {@link NumberStepper}: click steps up, shift- or
 *       right-click steps down, cmd-click types. A right-click anywhere else
 *       is swallowed, as it always was.</li>
 *   <li>While the panel is active and the click is on a cell, attack /
 *       use interactions are cancelled so the player doesn't accidentally
 *       break / place blocks while clicking the panel.</li>
 *   <li>Defers to the keyboard-opened {@link CommandMenuState} when both
 *       are open (matches part-menu behaviour).</li>
 * </ul>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class BlockVariantMenuInputHandler {

    private static boolean pressArmed;
    private static boolean pressShift;
    /** Which mouse button armed the press — only its own release dispatches. */
    private static int pressButton;

    /**
     * Ceiling for a typed weight. The stepper has no cap of its own — this is a sanity bound on
     * the number pad so a mistyped digit run can't drown every other entry in the pool.
     */
    private static final int TYPED_WEIGHT_MAX = 1000;

    private BlockVariantMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
        pressButton = event.isUseItem() ? GLFW.GLFW_MOUSE_BUTTON_RIGHT : GLFW.GLFW_MOUSE_BUTTON_LEFT;
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
        if (btn != GLFW.GLFW_MOUSE_BUTTON_LEFT && btn != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        if (event.getAction() != GLFW.GLFW_RELEASE) return;
        if (!pressArmed || btn != pressButton) return;
        pressArmed = false;
        boolean shift = pressShift
            || (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;

        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        dispatch(hit, shift, btn);
    }

    /**
     * Middle-click a "→ Group N" row: group N lands in the hotbar as a Variant Clipboard, ready to
     * Add as a reference on another cell or paste to rebuild the group. Ahead of
     * {@code VariantCopyPickClient} (whole-cell copy) and vanilla pick-block, and cancelled only
     * when it acted, so a middle-click anywhere else keeps its old meaning.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMiddleClick(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || event.getAction() != GLFW.GLFW_PRESS) return;
        if (!shouldHandle() || Minecraft.getInstance().screen != null) return;
        if (copyGroupRow(BlockVariantMenu.hovered())) event.setCanceled(true);
    }

    /**
     * Send the copy for {@code hit} when it is on a group-reference row of the variant list.
     * Package-visible for {@link BlockVariantMenuScreen}'s middle button. True when it sent one.
     */
    static boolean copyGroupRow(BlockVariantMenu.Hit hit) {
        if (BlockVariantMenu.screen() != BlockVariantMenu.Screen.ROOT) return false;
        if (!isRowCell(hit.kind())) return false;
        if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return false;
        if (!BlockVariantMenu.entries().get(hit.index()).isGroupRef()) return false;
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        if (local == null) return false;
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
            BlockVariantEditPacket.Op.COPY_GROUP_ENTRY, BlockVariantMenu.variantId(), local, hit.index(), "", 0));
        return true;
    }

    /** The cells drawn on a variant row, whose {@code index} is that row. */
    private static boolean isRowCell(BlockVariantMenu.CellKind kind) {
        return switch (kind) {
            case ENTRY_NAME, ENTRY_WEIGHT, ENTRY_REMOVE_X, ENTRY_ROT_MODE, ENTRY_ROT_DIRS,
                 ENTRY_HALF_MODE, ENTRY_ACTIVE_MODE, ENTRY_DIFF_MIN, ENTRY_DIFF_MAX -> true;
            default -> false;
        };
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        BlockVariantMenu.Hit hit = BlockVariantMenu.hovered();
        if (hit.kind() == BlockVariantMenu.CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BlockVariantSearchScreen
            && (!BlockVariantMenu.isActive()
                || BlockVariantMenu.screen() != BlockVariantMenu.Screen.ADD_SEARCH)) {
            mc.setScreen(null);
        }
        if (!BlockVariantMenu.isActiveWorldspace()) return;
        if (mc.screen == null) {
            BlockVariantMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!BlockVariantMenu.isActiveWorldspace()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    /**
     * Act on a hit cell. Package-visible because {@link BlockVariantMenuScreen} dispatches
     * through this same body — the screen-space path differs only in where the Hit came from.
     */
    static void dispatch(BlockVariantMenu.Hit hit, boolean shift, int button) {
        // Right-click is a number cell's step-down and nothing else — on any other cell it is
        // swallowed (the press was already cancelled so nothing is placed).
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !isNumberCell(hit)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        if (BlockVariantMenu.screen() == BlockVariantMenu.Screen.ROOT) {
            dispatchRoot(hit, shift, button);
        } else {
            dispatchSearch(hit);
        }
    }

    /** Whether {@code hit} is one of this panel's numbers. */
    private static boolean isNumberCell(BlockVariantMenu.Hit hit) {
        if (BlockVariantMenu.screen() != BlockVariantMenu.Screen.ROOT) return false;
        return switch (hit.kind()) {
            case ENTRY_WEIGHT, ENTRY_DIFF_MIN, ENTRY_DIFF_MAX -> true;
            case GROWTH_OPTION -> hit.secondary() >= 0 && GrowthPopupLayout.isNumber(hit.secondary() / 10);
            default -> false;
        };
    }

    private static void dispatchRoot(BlockVariantMenu.Hit hit, boolean shift, int button) {
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        if (local == null) return;
        String variantId = BlockVariantMenu.variantId();
        switch (hit.kind()) {
            case COPY -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.COPY, variantId, local, -1, "", 0));
            case SAVE -> {
                // Screen-space: come back to the panel we are replacing, not the world.
                Minecraft.getInstance().setScreen(
                    new games.brennan.dungeontrain.client.menu.PrefabNameScreen(
                        games.brennan.dungeontrain.client.menu.PrefabNameScreen.Kind.VARIANT,
                        local,
                        BlockVariantMenu.space().isScreenspace()
                            ? new BlockVariantMenuScreen() : null));
            }
            case ADD -> DungeonTrainNet.sendToServer(
                // Server captures the player's main-hand BlockItem — empty
                // stateString signals "use held item" rather than the legacy
                // search-screen path.
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.ADD, variantId, local, -1, "", 0));
            case LOCK -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CYCLE_LOCK_ID, variantId, local, -1, "", 0));
            case COPY_ROLL -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CYCLE_COPY_ROLL, variantId, local, -1, "", 0));
            case COPY_SCOPE -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CYCLE_COPY_SCOPE, variantId, local, -1, "", 0));
            case SPAN -> {
                BlockVariantMenu.closeRotPopup();
                BlockVariantMenu.closeGrowthPopup();
                BlockVariantMenu.toggleSpanPopup();
            }
            case ENTRY_GROWTH -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantMenu.closeRotPopup();
                BlockVariantMenu.closeSpanPopup();
                BlockVariantMenu.closeCondPopup();
                BlockVariantMenu.toggleGrowthPopup(hit.index());
            }
            case ENTRY_COND -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantMenu.closeRotPopup();
                BlockVariantMenu.closeSpanPopup();
                BlockVariantMenu.closeGrowthPopup();
                BlockVariantMenu.toggleCondPopup(hit.index());
            }
            case COND_OPTION -> dispatchCondition(hit, variantId, local, shift);
            case GROWTH_OPTION -> {
                // -2 = clicked elsewhere in the panel (close), -1 = the popup's own margin (ignore).
                // A button sets that one section and leaves the popup open for the next choice.
                int code = hit.secondary();
                if (code == -1) return;
                if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
                    BlockVariantMenu.closeGrowthPopup();
                    return;
                }
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                BlockState parsed = BlockVariantMenu.parseState(e.stateString());
                if (parsed == null) return;
                int row = hit.index();
                int section = code / 10;
                VariantGrowth current = VariantGrowth.fromInt(e.growth());
                if (GrowthPopupLayout.isNumber(section)) {
                    int value = GrowthPopupLayout.length(current, section);
                    java.util.function.IntConsumer setLength = v -> DungeonTrainNet.sendToServer(
                        new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_GROWTH, variantId, local, row, "",
                            GrowthPopupLayout.withLength(current, section, v).toInt()));
                    NumberStepper.click(new NumberField(
                            NumberTitle.of(section == GrowthPopupLayout.SECTION_MIN ? "grow_min" : "grow_max"),
                            value, 1, VariantGrowth.MAX_LENGTH, false,
                            () -> setLength.accept(value + 1), () -> setLength.accept(value - 1), setLength),
                        button, shift, MenuClickModifiers.cmdDown(), pad());
                    return;
                }
                VariantGrowth next = GrowthPopupLayout.apply(current, parsed, section, code % 10);
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_GROWTH, variantId, local, row, "", next.toInt()));
            }
            case PAINTING_OPTION -> {
                // Same modal rules as GROWTH_OPTION: -2 closes, -1 is the popup's own margin.
                int code = hit.secondary();
                if (code == -1) return;
                if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
                    BlockVariantMenu.closeGrowthPopup();
                    return;
                }
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                if (code / 10 != PaintingPopupLayout.SECTION_ON && code % 10 == PaintingPopupLayout.STEPPER_VALUE) return;
                games.brennan.dungeontrain.editor.VariantPainting current = BlockVariantMenu.painting(e);
                games.brennan.dungeontrain.editor.VariantPainting next = PaintingPopupLayout.apply(
                    current, BlockVariantMenu.pictures(), code / 10, code % 10);
                if (next.equals(current)) return;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_PAINTING, variantId, local, hit.index(),
                    next.isDefault() ? "" : next.id(), 0));
            }
            case SPAN_OPTION -> {
                // -2 = clicked elsewhere in the panel (close), -1 = the popup's own margin (ignore).
                // A button sets that one section and leaves the popup open for the next choice.
                int code = hit.secondary();
                if (code == -1) return;
                if (code < 0) {
                    BlockVariantMenu.closeSpanPopup();
                    return;
                }
                games.brennan.dungeontrain.editor.VariantSpan next = SpanPopupLayout.apply(
                    BlockVariantMenu.resolvedSpan(), code / 10, code % 10);
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_SPAN_MODE, variantId, local, -1, "", next.toByte()));
            }
            case REMOVE -> BlockVariantMenu.toggleRemoveMode();
            case CLEAR -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.CLEAR, variantId, local, -1, "", 0));
            case CLOSE -> DungeonTrainNet.sendToServer(new BlockVariantMenuTogglePacket(false));
            case ENTRY_WEIGHT, ENTRY_DIFF_MIN, ENTRY_DIFF_MAX -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                NumberField field = rowNumber(hit.kind(), variantId, local, hit.index(),
                    BlockVariantMenu.entries().get(hit.index()));
                NumberStepper.click(field, button, shift, MenuClickModifiers.cmdDown(), pad());
            }
            case ENTRY_REMOVE_X -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.REMOVE, variantId, local, hit.index(), "", 0));
            }
            case ENTRY_NAME -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                // Shift-click swaps the row for the block in hand; the server
                // reads the main-hand stack and keeps the row's other settings.
                if (shift) {
                    DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                        BlockVariantEditPacket.Op.REPLACE_WITH_HELD, variantId, local, hit.index(), "", 0));
                    return;
                }
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
            case ENTRY_ACTIVE_MODE -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                int currentOrd = e.activeMode() & 0xFF;
                int modeCount = games.brennan.dungeontrain.editor.VariantActive.Mode.values().length;
                if (currentOrd >= modeCount) {
                    currentOrd = games.brennan.dungeontrain.editor.VariantActive.Mode.INACTIVE.ordinal();
                }
                int nextOrd = (currentOrd + 1) % modeCount;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_ACTIVE_MODE, variantId, local, hit.index(), "", nextOrd));
            }
            case ENTRY_ARM_NORTH, ENTRY_ARM_EAST, ENTRY_ARM_SOUTH, ENTRY_ARM_WEST -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockState parsed = BlockVariantMenu.parseState(BlockVariantMenu.entries().get(hit.index()).stateString());
                if (parsed == null) return;
                // Flip the clicked arm — ARM_SEGMENTS is index-aligned with VariantConnect.ARM_BITS.
                int seg = java.util.Arrays.asList(BlockVariantMenu.ARM_SEGMENTS).indexOf(hit.kind());
                int arms = games.brennan.dungeontrain.editor.VariantConnect.armMask(parsed)
                    ^ games.brennan.dungeontrain.editor.VariantConnect.ARM_BITS[seg];
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_CONNECT_ARMS, variantId, local, hit.index(), "", arms));
            }
            case ENTRY_CONNECT_DEFAULT, ENTRY_CONNECT_AUTO, ENTRY_CONNECT_LOCK -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                // The segment clicked is the mode chosen — CONNECT_SEGMENTS is in Mode order.
                int ordinal = java.util.Arrays.asList(BlockVariantMenu.CONNECT_SEGMENTS).indexOf(hit.kind());
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_CONNECT_MODE, variantId, local, hit.index(), "", ordinal));
            }
            case ENTRY_ROT_DIRS -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                VariantRotation.Mode mode = decodeMode(e.rotMode());
                BlockState parsed = BlockVariantMenu.parseState(e.stateString());
                if (parsed == null) return;
                int validMask = RotationApplier.validDirMask(parsed);
                int currentMask = e.rotDirMask() & VariantRotation.ALL_SLOTS_MASK;
                if (mode == VariantRotation.Mode.LOCK) {
                    int nextMask = nextLockBit(currentMask, validMask, RotationApplier.slotCount(parsed));
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
                if (dirOrd < 0) return; // backdrop click — ignore (popup stays open)
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(row);
                BlockState parsed = BlockVariantMenu.parseState(e.stateString());
                if (parsed == null || dirOrd >= RotationApplier.slotCount(parsed)) return;
                int validMask = RotationApplier.validDirMask(parsed);
                int bit = 1 << dirOrd;
                if ((validMask & bit) == 0) return; // invalid for this block — no-op
                int currentMask = e.rotDirMask() & VariantRotation.ALL_SLOTS_MASK;
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

    /**
     * The number behind one row's weight or difficulty cell: click and shift/right-click send the
     * stepping op, cmd-click types an absolute value with its SET twin.
     */
    private static NumberField rowNumber(BlockVariantMenu.CellKind kind, String variantId,
                                         net.minecraft.core.BlockPos local, int row,
                                         BlockVariantSyncPacket.Entry entry) {
        record Ops(BlockVariantEditPacket.Op bump, BlockVariantEditPacket.Op set) {}
        Ops ops = switch (kind) {
            case ENTRY_DIFF_MIN -> new Ops(BlockVariantEditPacket.Op.BUMP_DIFF_MIN, BlockVariantEditPacket.Op.SET_DIFF_MIN);
            case ENTRY_DIFF_MAX -> new Ops(BlockVariantEditPacket.Op.BUMP_DIFF_MAX, BlockVariantEditPacket.Op.SET_DIFF_MAX);
            default -> new Ops(BlockVariantEditPacket.Op.BUMP_WEIGHT, BlockVariantEditPacket.Op.SET_WEIGHT);
        };
        Runnable inc = () -> send(ops.bump(), variantId, local, row, 1);
        Runnable dec = () -> send(ops.bump(), variantId, local, row, -1);
        java.util.function.IntConsumer set = v -> send(ops.set(), variantId, local, row, v);
        return switch (kind) {
            case ENTRY_DIFF_MIN -> new NumberField(NumberTitle.of("min_level"), entry.minDiff(), 0,
                entry.maxDiff() < 0 ? VariantDifficulty.MAX_TIER : entry.maxDiff(), false, inc, dec, set);
            case ENTRY_DIFF_MAX -> new NumberField(NumberTitle.of("max_level"),
                entry.maxDiff() < 0 ? NumberField.ALL : entry.maxDiff(), 0, VariantDifficulty.MAX_TIER, true,
                inc, dec, set);
            default -> new NumberField(NumberTitle.of("weight"), entry.weight(), 1, TYPED_WEIGHT_MAX, false,
                inc, dec, set);
        };
    }

    /**
     * A button in the If popup. Same modal rules as the Grow popup: -2 closes, -1 is the popup's own
     * margin. The popup stays open so several values can be added in a row.
     */
    private static void dispatchCondition(BlockVariantMenu.Hit hit, String variantId, net.minecraft.core.BlockPos local,
                                          boolean shift) {
        int code = hit.secondary();
        if (code == -1) return;
        int row = hit.index();
        if (code < 0 || row < 0 || row >= BlockVariantMenu.entries().size()) {
            BlockVariantMenu.closeCondPopup();
            return;
        }
        int section = code / 10;
        int option = code % 10;
        String token = BlockVariantMenu.entries().get(row).condition();
        boolean groupMode = ConditionPopupLayout.isGroupMode(token);
        BlockVariantEditPacket packet;
        if (section == ConditionPopupLayout.SECTION_MODE) {
            // Switching mode keeps the block list; Group picks the first other lock group.
            boolean toGroup = option == ConditionPopupLayout.MODE_GROUP;
            if (toGroup == groupMode) return;
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                toGroup ? games.brennan.dungeontrain.editor.VariantConditionEdits.GROUP_NEXT
                    : games.brennan.dungeontrain.editor.VariantCondition.DEFAULT_DIRECTION.getSerializedName(), 0);
        } else if (section == ConditionPopupLayout.SECTION_TOUCHING) {
            // Same clicks as the band picker: plain toggles this side, shift toggles all but this side.
            String side = ConditionPopupLayout.touching(option).getSerializedName();
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                (shift ? games.brennan.dungeontrain.editor.VariantConditionEdits.OTHERS_PREFIX
                    : games.brennan.dungeontrain.editor.VariantConditionEdits.TOGGLE_PREFIX) + side, 0);
        } else if (section == ConditionPopupLayout.SECTION_GROUP) {
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                ConditionPopupLayout.groupToken(option), 0);
        } else if (section == ConditionPopupLayout.SECTION_BLOCKS) {
            List<net.minecraft.resources.ResourceLocation> strip =
                ConditionPopupLayout.strip(BlockVariantMenu.entries().get(row));
            // The "–" placeholder of an empty strip does nothing.
            if (option >= strip.size()) return;
            // Group mode switches one of the group's blocks on or off; Touching mode drops the value.
            packet = groupMode
                ? new BlockVariantEditPacket(BlockVariantEditPacket.Op.TOGGLE_COND_VALUE, variantId, local, row,
                    strip.get(option).toString(), 0)
                : new BlockVariantEditPacket(BlockVariantEditPacket.Op.REMOVE_COND_VALUE, variantId, local, row,
                    "", option);
        } else if (section == ConditionPopupLayout.SECTION_ACTIONS) {
            // Group mode's action row is Clear alone.
            boolean add = !groupMode && option == ConditionPopupLayout.ACTION_ADD;
            packet = new BlockVariantEditPacket(add
                ? BlockVariantEditPacket.Op.ADD_COND_VALUE : BlockVariantEditPacket.Op.CLEAR_COND,
                variantId, local, row, "", 0);
        } else {
            return;
        }
        DungeonTrainNet.sendToServer(packet);
    }

    private static void send(BlockVariantEditPacket.Op op, String variantId, net.minecraft.core.BlockPos local,
                             int row, int value) {
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(op, variantId, local, row, "", value));
    }

    /**
     * Cmd-click types into the number pad, returning to the panel screen when the menu is
     * screen-space and to the world when it is world-space — same rule as the Save cell, whose
     * panel is a HUD overlay that is still drawn behind the modal.
     */
    private static NumberStepper.Typing pad() {
        return NumberPad.returningToBuilt(
            () -> BlockVariantMenu.space().isScreenspace() ? new BlockVariantMenuScreen() : null);
    }

    private static VariantRotation.Mode decodeMode(byte raw) {
        int ord = raw & 0xFF;
        VariantRotation.Mode[] values = VariantRotation.Mode.values();
        if (ord < 0 || ord >= values.length) return VariantRotation.Mode.RANDOM;
        return values[ord];
    }

    /**
     * Cycle to the next valid bit for LOCK mode. Walks the mask's slots
     * (Direction.ordinal(), or the 8 compass points for heads / banners /
     * signs) starting after the currently-set bit so repeated clicks move
     * through the property's allowed directions in order. Wraps around.
     */
    private static int nextLockBit(int currentMask, int validMask, int slotCount) {
        if (validMask == 0) return 0;
        int currentOrd = currentMask == 0 ? -1 : Integer.numberOfTrailingZeros(currentMask);
        for (int step = 1; step <= slotCount; step++) {
            int probe = (currentOrd + step) % slotCount;
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
            case SEARCH_FIELD -> {
                // World-space has no screen of its own, so it needs an invisible one to stop
                // typed letters from walking the player. Screen-space is already a screen and
                // types inline — opening this over it would replace the panel being typed into.
                if (BlockVariantMenu.space().isWorldspace()) {
                    Minecraft.getInstance().setScreen(new BlockVariantSearchScreen());
                }
            }
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
