package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.WorldPanelInput;
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

    /**
     * Ceiling for a typed weight. The stepper has no cap of its own — this is a sanity bound on
     * the number pad so a mistyped digit run can't drown every other entry in the pool.
     */
    private static final int TYPED_WEIGHT_MAX = 1000;

    /** One click on a chest row's Trap pill, in percent. */
    private static final int TRAP_STEP = 5;

    /**
     * Hover and clicks span both panels: the hit is whichever panel the crosshair is on (the stacked
     * group panel or the main one), and the click runs with the menu pointed at that panel, so every
     * packet it sends carries that panel's cell.
     */
    private static final WorldPanelInput<BlockVariantMenu.Hit> INPUT = new WorldPanelInput<>(
        BlockVariantMenu::hoveredAny, hit -> hit.kind() == BlockVariantMenu.CellKind.NONE,
        BlockVariantMenuInputHandler::shouldHandle, true,
        (hit, shift, button) -> BlockVariantMenu.withPanel(BlockVariantMenu.hoverPanel(),
            () -> dispatch(hit, shift, button)));

    private BlockVariantMenuInputHandler() {}

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
        boolean copied = BlockVariantMenu.fromPanel(BlockVariantMenu.hoverPanel(),
            () -> copyGroupRow(BlockVariantMenu.hovered()));
        if (copied) event.setCanceled(true);
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

    /** A right-click on any of a row's cells but its number cells, holding a water bucket — the waterlog toggle. */
    private static boolean isWaterBucketOnRow(BlockVariantMenu.Hit hit) {
        if (BlockVariantMenu.screen() != BlockVariantMenu.Screen.ROOT || !isRowCell(hit.kind()) || isNumberCell(hit)) return false;
        if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return false;
        net.minecraft.client.player.LocalPlayer me = Minecraft.getInstance().player;
        return me != null && me.getMainHandItem().is(net.minecraft.world.item.Items.WATER_BUCKET);
    }

    /** The cells drawn on a variant row, whose {@code index} is that row. */
    private static boolean isRowCell(BlockVariantMenu.CellKind kind) {
        return switch (kind) {
            case ENTRY_NAME, ENTRY_WEIGHT, ENTRY_REMOVE_X, ENTRY_ROT_MODE, ENTRY_ROT_DIRS,
                 ENTRY_HALF_MODE, ENTRY_ACTIVE_MODE, ENTRY_VILLAGER_MODE, ENTRY_DIFF_MIN, ENTRY_DIFF_MAX -> true;
            default -> false;
        };
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
        // Right-click (no shift) on a "+ Group N" row opens group N's menu stacked above this one.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !shift && openGroupPanel(hit)) return;
        // Otherwise right-click is a number cell's step-down and nothing else — on any other cell
        // it is swallowed (the press was already cancelled so nothing is placed).
        // Right-click anywhere on a row (name, On/R/Off, half, rotation…) holding a water bucket
        // waterlogs that row's block, or dries it. Number cells keep right-click as their step-down.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && isWaterBucketOnRow(hit)) {
            net.minecraft.core.BlockPos waterAt = BlockVariantMenu.localPos();
            if (waterAt != null) {
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.TOGGLE_WATERLOGGED, BlockVariantMenu.variantId(), waterAt, hit.index(), "", 0));
            }
            return;
        }
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

    /**
     * Right-click on a group-reference row: open that group's menu stacked above, or close it when
     * it is the group already shown there. A group row inside the stacked panel swaps it. World-space
     * only — the screen-space host draws a single panel. True when it acted.
     */
    private static boolean openGroupPanel(BlockVariantMenu.Hit hit) {
        if (!BlockVariantMenu.space().isWorldspace()) return false;
        if (BlockVariantMenu.screen() != BlockVariantMenu.Screen.ROOT || !isRowCell(hit.kind())) return false;
        // The weight / difficulty cells keep right-click as their step-down.
        if (isNumberCell(hit)) return false;
        int row = hit.index();
        if (row < 0 || row >= BlockVariantMenu.entries().size()) return false;
        BlockVariantSyncPacket.Entry entry = BlockVariantMenu.entries().get(row);
        if (!entry.isGroupRef()) return false;
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        if (local == null) return false;
        boolean alreadyShown = BlockVariantMenu.childLockId() == entry.groupRef();
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
            alreadyShown ? BlockVariantEditPacket.Op.CLOSE_GROUP_MENU : BlockVariantEditPacket.Op.OPEN_GROUP_MENU,
            BlockVariantMenu.variantId(), local, row, "", 0));
        return true;
    }

    /** Whether {@code hit} is one of this panel's numbers. */
    private static boolean isNumberCell(BlockVariantMenu.Hit hit) {
        if (BlockVariantMenu.screen() != BlockVariantMenu.Screen.ROOT) return false;
        return switch (hit.kind()) {
            case ENTRY_WEIGHT, ENTRY_DIFF_MIN, ENTRY_DIFF_MAX -> true;
            case ENTRY_GROWTH -> isTrapRow(hit.index());
            case GROWTH_OPTION -> hit.secondary() >= 0 && GrowthPopupLayout.isNumber(GrowthPopupLayout.section(hit.secondary()));
            case CHARGE_OPTION -> hit.secondary() >= 0 && ChargePopupLayout.isNumber(GrowthPopupLayout.section(hit.secondary()));
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
            case ROT_LOCK -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.TOGGLE_ROTATION_FREE, variantId, local, -1, "", 0));
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
                // A chest row's Grow slot is its Trap number: step it, no popup.
                if (isTrapRow(hit.index())) {
                    NumberStepper.click(trapNumber(variantId, local, hit.index(),
                        BlockVariantMenu.entries().get(hit.index())), button, shift, MenuClickModifiers.cmdDown(), pad());
                    return;
                }
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
                boolean opening = BlockVariantMenu.condPopupRow() != hit.index();
                BlockVariantMenu.toggleCondPopup(hit.index());
                // An empty row opens holding the condition last changed in this cell (server decides).
                String token = BlockVariantMenu.entries().get(hit.index()).condition();
                if (opening && games.brennan.dungeontrain.editor.VariantCondition.parse(token).isDefault()) {
                    send(BlockVariantEditPacket.Op.PREFILL_COND, variantId, local, hit.index(), 0);
                }
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
                int section = GrowthPopupLayout.section(code);
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
                VariantGrowth next = GrowthPopupLayout.apply(current, parsed, section, GrowthPopupLayout.option(code));
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
                if (GrowthPopupLayout.section(code) != PaintingPopupLayout.SECTION_ON && GrowthPopupLayout.option(code) == PaintingPopupLayout.STEPPER_VALUE) return;
                games.brennan.dungeontrain.editor.VariantPainting current = BlockVariantMenu.painting(e);
                games.brennan.dungeontrain.editor.VariantPainting next = PaintingPopupLayout.apply(
                    current, BlockVariantMenu.pictures(), GrowthPopupLayout.section(code), GrowthPopupLayout.option(code));
                if (next.equals(current)) return;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_PAINTING, variantId, local, hit.index(),
                    next.isDefault() ? "" : next.id(), 0));
            }
            case CANDLE_OPTION -> {
                // Same modal rules as GROWTH_OPTION: -2 closes, -1 is the popup's own margin.
                int code = hit.secondary();
                if (code == -1) return;
                if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
                    BlockVariantMenu.closeGrowthPopup();
                    return;
                }
                games.brennan.dungeontrain.editor.VariantCandles current =
                    BlockVariantMenu.candles(BlockVariantMenu.entries().get(hit.index()));
                games.brennan.dungeontrain.editor.VariantCandles next =
                    CandlePopupLayout.apply(GrowthPopupLayout.option(code));
                if (next.equals(current)) return;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_CANDLES, variantId, local, hit.index(), next.id(), 0));
            }
            case POT_OPTION -> dispatchPot(hit, variantId, local);
            case SIGN_OPTION -> dispatchSign(hit, variantId, local);
            case CHARGE_OPTION -> dispatchCharges(hit, variantId, local, shift, button);
            case BED_COLOUR_OPTION -> dispatchBedColours(hit, variantId, local);
            case SLIME_OPTION -> dispatchSlime(hit, variantId, local);
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
                    BlockVariantMenu.resolvedSpan(), GrowthPopupLayout.section(code), GrowthPopupLayout.option(code));
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
                // Right-click holding a water bucket waterlogs the row's block (or dries it).
                net.minecraft.client.player.LocalPlayer me = net.minecraft.client.Minecraft.getInstance().player;
                if (button == 1 && me != null && me.getMainHandItem().is(net.minecraft.world.item.Items.WATER_BUCKET)) {
                    DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                        BlockVariantEditPacket.Op.TOGGLE_WATERLOGGED, variantId, local, hit.index(), "", 0));
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
            case ENTRY_VILLAGER_MODE -> {
                if (hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) return;
                BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
                games.brennan.dungeontrain.editor.VariantVillager.Mode next =
                    games.brennan.dungeontrain.editor.VariantVillager.Mode.fromOrdinal(e.villagerMode() & 0xFF).next();
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_VILLAGER_MODE, variantId, local, hit.index(), "", next.ordinal()));
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
    /**
     * A respawn anchor row's Charge popup. Same modal rules as the Grow popup: -2 closes, -1 is the
     * popup's own margin. As is / Range set the mode; Min / Max are number cells (click steps,
     * shift- or right-click steps down, cmd-click types).
     */
    private static void dispatchCharges(BlockVariantMenu.Hit hit, String variantId,
                                        net.minecraft.core.BlockPos local, boolean shift, int button) {
        int code = hit.secondary();
        if (code == -1) return;
        if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
            BlockVariantMenu.closeGrowthPopup();
            return;
        }
        int row = hit.index();
        int section = GrowthPopupLayout.section(code);
        games.brennan.dungeontrain.editor.VariantCharges current =
            BlockVariantMenu.charges(BlockVariantMenu.entries().get(row));
        int max = games.brennan.dungeontrain.editor.VariantCharges.maxFor(
            BlockVariantMenu.parseState(BlockVariantMenu.entries().get(row).stateString()));
        if (ChargePopupLayout.isNumber(section)) {
            int value = ChargePopupLayout.value(current, section);
            java.util.function.IntConsumer setValue = v -> DungeonTrainNet.sendToServer(
                new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_CHARGES, variantId, local, row, "",
                    ChargePopupLayout.withValue(current, section, v, max).toInt()));
            String kind = ChargePopupLayout.isGems(max) ? "gems" : "charges";
            NumberStepper.click(new NumberField(
                    NumberTitle.of(kind + (section == ChargePopupLayout.SECTION_MIN ? "_min" : "_max")),
                    value, 0, max, false,
                    () -> setValue.accept(value + 1), () -> setValue.accept(value - 1), setValue),
                button, shift, MenuClickModifiers.cmdDown(), pad());
            return;
        }
        games.brennan.dungeontrain.editor.VariantCharges next =
            ChargePopupLayout.applyMode(current, GrowthPopupLayout.option(code), max);
        if (next.equals(current)) return;
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
            BlockVariantEditPacket.Op.SET_CHARGES, variantId, local, row, "", next.toInt()));
    }

    /**
     * A white bed row's Colours popup: a swatch switches its colour on or off. Clearing the last
     * enabled colour is ignored, so one always stays on. Same modal rules as the Grow popup.
     */
    /**
     * A sign row's Sign popup: the icon row toggles the lock and the wax and opens
     * {@link SignTextScreen}; Mount switches the block (or turns Auto on); Text sets the cell's
     * shared text. Same modal rules as the Grow popup.
     */
    private static void dispatchSign(BlockVariantMenu.Hit hit, String variantId,
                                     net.minecraft.core.BlockPos local) {
        int code = hit.secondary();
        if (code == -1) return;
        if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
            BlockVariantMenu.closeGrowthPopup();
            return;
        }
        BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(hit.index());
        net.minecraft.world.level.block.state.BlockState parsed = BlockVariantMenu.parseState(e.stateString());
        if (!BlockVariantMenu.isSignRow(e, parsed)) return;
        int option = GrowthPopupLayout.option(code);
        games.brennan.dungeontrain.editor.VariantSign sign = e.signOptions();
        games.brennan.dungeontrain.editor.SignVariants.Text text = BlockVariantMenu.signText(e);
        int section = GrowthPopupLayout.section(code);
        if (SignPopupLayout.isMountSection(section)) {
            int[] wires = SignPopupLayout.mountWires(parsed);
            int button = SignPopupLayout.mountButton(section, option);
            if (button < 0 || button >= wires.length) return;
            warnIfTooWide(text, wires[button]);
            DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                BlockVariantEditPacket.Op.SET_SIGN_MOUNT, variantId, local, hit.index(), "", wires[button]));
            return;
        }
        switch (section) {
            case SignPopupLayout.SECTION_TEXT -> {
                boolean shared = option == SignPopupLayout.TEXT_SHARED;
                if (shared == sign.shared()) return;
                DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                    BlockVariantEditPacket.Op.SET_SIGN_SHARED, variantId, local, hit.index(), "", shared ? 1 : 0));
            }
            case SignPopupLayout.SECTION_TOGGLES -> {
                switch (option) {
                    case SignPopupLayout.TOGGLE_LOCK -> DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                        BlockVariantEditPacket.Op.SET_SIGN_LOCKED, variantId, local, hit.index(), "",
                        sign.locked() ? 0 : 1));
                    case SignPopupLayout.TOGGLE_WAX -> DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
                        BlockVariantEditPacket.Op.SET_SIGN_WAXED, variantId, local, hit.index(), "",
                        text.waxed() ? 0 : 1));
                    case SignPopupLayout.TOGGLE_EDIT -> openSignText(hit.index(), e, parsed, variantId, local);
                    default -> { }
                }
            }
            default -> { }
        }
    }

    /**
     * A hanging sign's lines are narrower than a sign's; text written for the wider one is cut
     * off when it hangs. Say so when a mount switch (or Auto, which may hang) would do that.
     */
    private static void warnIfTooWide(games.brennan.dungeontrain.editor.SignVariants.Text text, int wire) {
        boolean mayHang = wire == games.brennan.dungeontrain.editor.SignVariants.AUTO_WIRE
            || games.brennan.dungeontrain.editor.SignVariants.isHanging(
                games.brennan.dungeontrain.editor.SignVariants.Mount.values()[wire]);
        if (!mayHang) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        java.util.List<String> lines = new java.util.ArrayList<>(text.front().lines());
        lines.addAll(text.back().lines());
        for (String line : lines) {
            if (mc.font.width(line) > games.brennan.dungeontrain.editor.SignVariants.HANGING_LINE_WIDTH) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    games.brennan.dungeontrain.client.menu.MenuLang.t("block_variant.sign_too_wide")), true);
                return;
            }
        }
    }

    /** Open the sign text screen for row {@code index}, sized to the narrowest sign it can land on. */
    private static void openSignText(int index, BlockVariantSyncPacket.Entry e,
                                     net.minecraft.world.level.block.state.BlockState parsed,
                                     String variantId, net.minecraft.core.BlockPos local) {
        boolean hanging = false;
        int shared = 0;
        java.util.List<BlockVariantSyncPacket.Entry> all = BlockVariantMenu.entries();
        for (int i = 0; i < all.size(); i++) {
            BlockVariantSyncPacket.Entry other = all.get(i);
            boolean target = i == index || (e.signOptions().shared() && other.signOptions().shared());
            if (!target) continue;
            net.minecraft.world.level.block.state.BlockState st = BlockVariantMenu.parseState(other.stateString());
            if (!BlockVariantMenu.isSignRow(other, st)) continue;
            shared++;
            games.brennan.dungeontrain.editor.SignVariants.Mount m = games.brennan.dungeontrain.editor.SignVariants.mountOf(st);
            // Auto can land on a hanging sign, so it gets the narrower width too.
            if (other.signOptions().auto() || (m != null && games.brennan.dungeontrain.editor.SignVariants.isHanging(m))) {
                hanging = true;
            }
        }
        int width = hanging ? games.brennan.dungeontrain.editor.SignVariants.HANGING_LINE_WIDTH
            : games.brennan.dungeontrain.editor.SignVariants.SIGN_LINE_WIDTH;
        Minecraft.getInstance().setScreen(new SignTextScreen(variantId, local, index,
            BlockVariantMenu.signText(e), width, Math.max(1, shared),
            BlockVariantMenu.space().isScreenspace() ? new BlockVariantMenuScreen() : null));
    }

    private static void dispatchBedColours(BlockVariantMenu.Hit hit, String variantId,
                                           net.minecraft.core.BlockPos local) {
        int code = hit.secondary();
        if (code == -1) return;
        if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
            BlockVariantMenu.closeGrowthPopup();
            return;
        }
        games.brennan.dungeontrain.editor.VariantBedColours current =
            BlockVariantMenu.bedColours(BlockVariantMenu.entries().get(hit.index()));
        games.brennan.dungeontrain.editor.VariantBedColours next = current.toggled(
            BedColourPopupLayout.colour(GrowthPopupLayout.section(code), GrowthPopupLayout.option(code)));
        if (next.equals(current)) return;
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
            BlockVariantEditPacket.Op.SET_BED_COLOURS, variantId, local, hit.index(), "", next.mask()));
    }

    /**
     * A slime-type row's Slime popup: a size or End slime type button switches it in or out of the
     * spawn's pool (none lit = any). Same modal rules as the Grow popup.
     */
    private static void dispatchSlime(BlockVariantMenu.Hit hit, String variantId,
                                      net.minecraft.core.BlockPos local) {
        int code = hit.secondary();
        if (code == -1) return;
        if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
            BlockVariantMenu.closeGrowthPopup();
            return;
        }
        games.brennan.dungeontrain.editor.VariantSlime current =
            BlockVariantMenu.slime(BlockVariantMenu.entries().get(hit.index()));
        games.brennan.dungeontrain.editor.VariantSlime next = SlimePopupLayout.toggled(
            current, GrowthPopupLayout.section(code), GrowthPopupLayout.option(code));
        if (next.equals(current)) return;
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
            BlockVariantEditPacket.Op.SET_SLIME, variantId, local, hit.index(), "", next.mask()));
    }

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

    /** True when row {@code index} is a plain chest row, whose Grow slot is the Trap number. */
    private static boolean isTrapRow(int index) {
        if (index < 0 || index >= BlockVariantMenu.entries().size()) return false;
        BlockVariantSyncPacket.Entry e = BlockVariantMenu.entries().get(index);
        return BlockVariantMenu.isTrapRow(e, BlockVariantMenu.parseState(e.stateString()));
    }

    /** The Trap pill as a number: click +5, right-click -5 (wrapping 0..100), cmd-click types it. */
    private static NumberField trapNumber(String variantId, net.minecraft.core.BlockPos local, int row,
                                          BlockVariantSyncPacket.Entry entry) {
        return new NumberField(NumberTitle.of("trapped_chance"), entry.trapped(), 0,
            games.brennan.dungeontrain.editor.VariantState.MAX_TRAPPED, false,
            () -> send(BlockVariantEditPacket.Op.BUMP_TRAPPED, variantId, local, row, TRAP_STEP),
            () -> send(BlockVariantEditPacket.Op.BUMP_TRAPPED, variantId, local, row, -TRAP_STEP),
            v -> send(BlockVariantEditPacket.Op.SET_TRAPPED, variantId, local, row, v));
    }

    /**
     * A click in a flower pot row's Plant popup. Same modal rules as the Grow popup: -2 closes,
     * -1 is the popup's own margin. The browse stepper moves client-side; everything else is an
     * edit the server applies to the row's {@code VariantPot}.
     */
    private static void dispatchPot(BlockVariantMenu.Hit hit, String variantId, net.minecraft.core.BlockPos local) {
        int code = hit.secondary();
        if (code == -1) return;
        if (code < 0 || hit.index() < 0 || hit.index() >= BlockVariantMenu.entries().size()) {
            BlockVariantMenu.closeGrowthPopup();
            return;
        }
        int row = hit.index();
        games.brennan.dungeontrain.editor.VariantPot pot = BlockVariantMenu.pot(BlockVariantMenu.entries().get(row));
        int section = GrowthPopupLayout.section(code);
        int option = GrowthPopupLayout.option(code);
        BlockVariantEditPacket.Op op;
        String arg;
        if (section == PotPopupLayout.SECTION_ON) {
            if ((option == 1) == pot.on()) return;
            op = BlockVariantEditPacket.Op.SET_POT;
            arg = option == 1 ? "random" : "";
        } else if (section == PotPopupLayout.SECTION_BROWSE) {
            if (option == PotPopupLayout.BROWSE_PREV || option == PotPopupLayout.BROWSE_NEXT) {
                PotPopupLayout.stepBrowse(option == PotPopupLayout.BROWSE_PREV ? -1 : 1);
                return;
            }
            net.minecraft.resources.ResourceLocation shown = PotPopupLayout.browsed();
            if (shown == null) return;
            op = BlockVariantEditPacket.Op.ADD_POT_PLANT;
            arg = shown.toString();
        } else if (section == PotPopupLayout.SECTION_ACTIONS) {
            if (option == PotPopupLayout.ACTION_RANDOM) {
                if (pot.isRandom()) return;
                op = BlockVariantEditPacket.Op.SET_POT;
                arg = "random";
            } else {
                op = BlockVariantEditPacket.Op.ADD_POT_PLANT;
                arg = "";
            }
        } else {
            net.minecraft.resources.ResourceLocation plant = PotPopupLayout.listedPlant(pot, section, option);
            if (plant == null) return;
            op = BlockVariantEditPacket.Op.REMOVE_POT_PLANT;
            arg = plant.toString();
        }
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(op, variantId, local, row, arg, 0));
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
        int section = GrowthPopupLayout.section(code);
        int option = GrowthPopupLayout.option(code);
        String token = BlockVariantMenu.entries().get(row).condition();
        int mode = ConditionPopupLayout.mode(token);
        boolean addable = mode == ConditionPopupLayout.MODE_TOUCHING;
        BlockVariantEditPacket packet;
        if (section == ConditionPopupLayout.SECTION_MODE) {
            // Touching <-> Group keeps the block list; Group picks the first other lock group. Kind
            // swaps the blocks for Solid, or back.
            if (option == mode) return;
            String target = switch (option) {
                case ConditionPopupLayout.MODE_GROUP -> games.brennan.dungeontrain.editor.VariantConditionEdits.GROUP_NEXT;
                case ConditionPopupLayout.MODE_KIND -> games.brennan.dungeontrain.editor.VariantConditionEdits.MODE_KIND;
                default -> games.brennan.dungeontrain.editor.VariantConditionEdits.MODE_TOUCHING;
            };
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                target, 0);
        } else if (section == ConditionPopupLayout.SECTION_TOUCHING) {
            // Same clicks as the band picker: plain toggles this side, shift toggles all but this side.
            String side = ConditionPopupLayout.touching(option).getSerializedName();
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                (shift ? games.brennan.dungeontrain.editor.VariantConditionEdits.OTHERS_PREFIX
                    : games.brennan.dungeontrain.editor.VariantConditionEdits.TOGGLE_PREFIX) + side, 0);
        } else if (section == ConditionPopupLayout.SECTION_MATCH) {
            // Any | All: one listed side is enough, or every listed side must hold a value.
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                games.brennan.dungeontrain.editor.VariantConditionEdits.MATCH_PREFIX
                    + (option == ConditionPopupLayout.MATCH_ALL ? "all" : "any"), 0);
        } else if (section == ConditionPopupLayout.SECTION_NEGATE) {
            // Is | Is not: place the entry when the target is one of the values, or when it is not.
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                games.brennan.dungeontrain.editor.VariantConditionEdits.NOT_PREFIX
                    + (option == ConditionPopupLayout.NEGATE_NOT ? "on" : "off"), 0);
        } else if (section == ConditionPopupLayout.SECTION_GROUP) {
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.SET_COND_TARGET, variantId, local, row,
                ConditionPopupLayout.groupToken(option), 0);
        } else if (section == ConditionPopupLayout.SECTION_BLOCKS) {
            List<net.minecraft.resources.ResourceLocation> strip =
                ConditionPopupLayout.strip(BlockVariantMenu.entries().get(row));
            // The "–" placeholder of an empty strip does nothing.
            if (option >= strip.size()) return;
            // Both modes switch one of the offered blocks on or off.
            // By id, not index: the strip leaves out the kind values, so its slots are not list indices.
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.TOGGLE_COND_VALUE, variantId, local, row,
                strip.get(option).toString(), 0);
        } else if (section == ConditionPopupLayout.SECTION_KIND) {
            packet = new BlockVariantEditPacket(BlockVariantEditPacket.Op.TOGGLE_COND_VALUE, variantId, local, row,
                ConditionPopupLayout.kind(option).toString(), 0);
        } else if (section == ConditionPopupLayout.SECTION_ACTIONS) {
            // Group and Kind modes' action row is Clear alone.
            boolean add = addable && option == ConditionPopupLayout.ACTION_ADD;
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
