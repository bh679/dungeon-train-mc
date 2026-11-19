package games.brennan.dungeontrain.client.menu.parts;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.WorldPanelInput;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.number.NumberField;
import games.brennan.dungeontrain.client.menu.number.NumberPad;
import games.brennan.dungeontrain.client.menu.number.NumberStepper;
import games.brennan.dungeontrain.client.menu.number.NumberTitle;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PartAssignmentEditPacket;
import games.brennan.dungeontrain.net.PartMenuTogglePacket;
import games.brennan.dungeontrain.train.CarriagePartAssignment.WeightedName;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
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
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class PartPositionMenuInputHandler {

    private static final WorldPanelInput<PartPositionMenu.Hit> INPUT = new WorldPanelInput<>(
        PartPositionMenu::hovered, hit -> hit.kind() == PartPositionMenu.CellKind.NONE,
        PartPositionMenuInputHandler::shouldHandle, true, PartPositionMenuInputHandler::dispatch);

    private PartPositionMenuInputHandler() {}

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

    /** Cancel the world-targeted attack/use when a press lands on the panel. Mirrors CommandMenuInputHandler. */

    /**
     * Belt-and-braces guard: if a click somehow slips past
     * {@link InputEvent.InteractionKeyMappingTriggered}, cancel the
     * left-click-block event so block damage doesn't begin.
     */

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
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
        if (mc.screen == null && !CommandMenuState.isOpen()) {
            PartPositionMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!PartPositionMenu.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    private static void dispatch(PartPositionMenu.Hit hit, boolean shift, int button) {
        // Right-click is a number cell's step-down and nothing else.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !isNumberCell(hit)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        if (PartPositionMenu.screen() == PartPositionMenu.Screen.ROOT) {
            dispatchRoot(hit, shift, button);
        } else {
            dispatchSearch(hit);
        }
    }

    /** Whether {@code hit} is one of this panel's numbers. */
    private static boolean isNumberCell(PartPositionMenu.Hit hit) {
        if (PartPositionMenu.screen() != PartPositionMenu.Screen.ROOT) return false;
        return switch (hit.kind()) {
            case ENTRY_WEIGHT, ENTRY_MIN_LEVEL, ENTRY_MAX_LEVEL -> true;
            default -> false;
        };
    }

    /**
     * One entry's weight or level bound: steps with the BUMP op, types with its SET twin. The
     * panel is a HUD overlay, so the pad returns to the world with it still drawn.
     */
    private static NumberField entryNumber(PartPositionMenu.CellKind cell, String variantId,
                                           CarriagePartKind kind, WeightedName e) {
        record Spec(String title, int value, int min, int max, boolean all,
                    PartAssignmentEditPacket.Op bump, PartAssignmentEditPacket.Op set) {}
        Spec spec = switch (cell) {
            case ENTRY_MIN_LEVEL -> new Spec("min_level", e.gate().minLevel(), 0, TemplateGate.MAX_LEVEL, false,
                PartAssignmentEditPacket.Op.BUMP_MIN_LEVEL, PartAssignmentEditPacket.Op.SET_MIN_LEVEL);
            case ENTRY_MAX_LEVEL -> new Spec("max_level",
                e.gate().maxLevel() == TemplateGate.ALL ? NumberField.ALL : e.gate().maxLevel(),
                e.gate().minLevel(), TemplateGate.MAX_LEVEL, true,
                PartAssignmentEditPacket.Op.BUMP_MAX_LEVEL, PartAssignmentEditPacket.Op.SET_MAX_LEVEL);
            default -> new Spec("weight", e.weight(), CarriagePartAssignment.MIN_WEIGHT,
                CarriagePartAssignment.MAX_WEIGHT, false,
                PartAssignmentEditPacket.Op.BUMP_WEIGHT, PartAssignmentEditPacket.Op.SET_WEIGHT);
        };
        String name = e.name();
        return new NumberField(NumberTitle.of(spec.title()), spec.value(), spec.min(), spec.max(), spec.all(),
            () -> DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(spec.bump(), variantId, kind, name, 1)),
            () -> DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(spec.bump(), variantId, kind, name, -1)),
            v -> DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(spec.set(), variantId, kind, name, v)));
    }

    private static void dispatchRoot(PartPositionMenu.Hit hit, boolean shift, int button) {
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
            case CLEAR -> DungeonTrainNet.sendToServer(
                new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.CLEAR, variantId, kind, "", 0));
            case CLOSE -> DungeonTrainNet.sendToServer(new PartMenuTogglePacket(false));
            case ENTRY_WEIGHT, ENTRY_MIN_LEVEL, ENTRY_MAX_LEVEL -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                NumberStepper.click(entryNumber(hit.kind(), variantId, kind, entries.get(hit.index())),
                    button, shift, MenuClickModifiers.cmdDown(), NumberPad.returningTo(null));
            }
            case ENTRY_STAGE -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                WeightedName e = entries.get(hit.index());
                // Open the Stage / Custom picker for this entry; it sends a SET_STAGE edit on pick.
                CommandMenuState.openAt(games.brennan.dungeontrain.client.menu.StagePickerScreen.forParts(
                    variantId, kind, e.name(), e.stageId()));
            }
            case ENTRY_PHASE -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                if (hit.phaseSlot() < 0) return;
                String name = entries.get(hit.index()).name();
                // delta carries the TrainPhase ordinal (see PartAssignmentEditPacket).
                // Plain click toggles that dimension; shift-click toggles all but that one.
                PartAssignmentEditPacket.Op op = shift
                    ? PartAssignmentEditPacket.Op.TOGGLE_OTHER_PHASES
                    : PartAssignmentEditPacket.Op.TOGGLE_PHASE;
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    op, variantId, kind, name, hit.phaseSlot()));
            }
            case ENTRY_REMOVE_X -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.REMOVE, variantId, kind, name, 0));
            }
            case ENTRY_SIDE_MODE -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.CYCLE_SIDE_MODE, variantId, kind, name, 0));
            }
            case ENTRY_END_MODE -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.CYCLE_END_MODE, variantId, kind, name, 0));
            }
            case ENTRY_NAME -> {
                List<WeightedName> entries = PartPositionMenu.entries();
                if (hit.index() < 0 || hit.index() >= entries.size()) return;
                String name = entries.get(hit.index()).name();
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.PREVIEW_ENTRY, variantId, kind, name, 0));
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
                DungeonTrainNet.sendToServer(new PartAssignmentEditPacket(
                    PartAssignmentEditPacket.Op.ADD, variantId, kind, filtered.get(hit.index()), 0));
                PartPositionMenu.backToRoot();
            }
            default -> {}
        }
    }
}
