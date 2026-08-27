package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CarriageContentsAllowScreen;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.Hovered;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorPlotActionPacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.List;

/**
 * Mouse wiring for the floating editor-plot control panels.
 *
 * <p>Mirrors {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenuInputHandler}
 * exactly: cancel attack/use on press over a panel cell, dispatch on left-click
 * release, defer when the keyboard menu or part menu is open. The press-arm /
 * release-dispatch dance keeps the menu from triggering on a stray click that
 * also breaks a block.</p>
 *
 * <p>Click dispatch:
 * <ul>
 *   <li>Weight {@code -}/{@code +} → existing slash command via
 *       {@link CommandRunner} (server already supports id-bearing weight).</li>
 *   <li>Save/Reset/Clear → new {@link EditorPlotActionPacket} so the action
 *       targets the panel's specific plot regardless of player position.</li>
 *   <li>Contents → opens {@link CarriageContentsAllowScreen} via the keyboard
 *       menu stack, drilled in to the carriage's modelId.</li>
 * </ul></p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorPlotPanelInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** True only when a press fired while the panel was hovered and we're awaiting its release. */
    private static boolean pressArmed;

    private EditorPlotPanelInputHandler() {}

    /** Cancel the world-targeted attack/use when a press lands on a panel cell. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        Hovered hit = EditorPlotLabelsRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
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

        Hovered hit = EditorPlotLabelsRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
        dispatch(hit);
    }

    /** Belt-and-braces — block any LeftClickBlock that slipped past the press cancel. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        if (EditorPlotLabelsRenderer.hovered().cell() == CellKind.NONE) return;
        event.setCanceled(true);
    }

    /** Refresh hover even outside the per-frame render so press arming is current. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!shouldHandle()) return;
        if (Minecraft.getInstance().screen == null) {
            EditorPlotPanelRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!EditorStatusHudOverlay.isEditorMenusVisible()) return false;
        if (EditorPlotLabelsRenderer.entries().isEmpty()) return false;
        // Defer to the keyboard menu and the part-position menu when either is
        // taking input — both have their own click handlers we'd otherwise
        // double-fire with.
        if (CommandMenuState.isOpen()) return false;
        if (PartPositionMenu.isActive()) return false;
        return true;
    }

    private static void dispatch(Hovered hit) {
        List<EditorPlotLabelsPacket.Entry> entries = EditorPlotLabelsRenderer.entries();
        if (hit.entryIndex() < 0 || hit.entryIndex() >= entries.size()) return;
        EditorPlotLabelsPacket.Entry entry = entries.get(hit.entryIndex());

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        switch (hit.cell()) {
            case NAME -> dispatchTeleport(entry);
            case WEIGHT_DEC -> dispatchWeight(entry, "dec");
            case WEIGHT_INC -> dispatchWeight(entry, "inc");
            case LENGTH_DEC -> dispatchDimension(entry, "length", "dec");
            case LENGTH_INC -> dispatchDimension(entry, "length", "inc");
            case WIDTH_DEC -> dispatchDimension(entry, "width", "dec");
            case WIDTH_INC -> dispatchDimension(entry, "width", "inc");
            case HEIGHT_DEC -> dispatchDimension(entry, "height", "dec");
            case HEIGHT_INC -> dispatchDimension(entry, "height", "inc");
            case LENGTH_TYPE -> openAxisEntry(entry, "length", "Length", entry.roomLength());
            case WIDTH_TYPE -> openAxisEntry(entry, "width", "Width", entry.roomWidth());
            case HEIGHT_TYPE -> openAxisEntry(entry, "height", "Height", entry.roomHeight());
            case MODE_CYCLE -> dispatchModeCycle(entry);
            case COPIES_CYCLE -> dispatchCopiesCycle(entry);
            case COPIES_FLOOR_HELD -> dispatchCopiesBlockHeld(entry,
                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR);
            case COPIES_FLOOR_EDIT -> dispatchCopiesBlockEdit(entry,
                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.FLOOR);
            case COPIES_ROOF_HELD -> dispatchCopiesBlockHeld(entry,
                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.ROOF);
            case COPIES_ROOF_EDIT -> dispatchCopiesBlockEdit(entry,
                games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane.ROOF);
            case ROOM_CONTENTS_CYCLE -> dispatchRoomContentsCycle(entry);
            case ROOM_BOOKS_CYCLE -> dispatchRoomBooksCycle(entry);
            case ROOM_BOOKS_EDIT -> openBookMix(entry);
            case DOOR_WALL_CYCLE -> dispatchDoorWallCycle(entry);
            case DOOR_OFFSET_DEC -> dispatchDoorOffset(entry, "dec");
            case DOOR_OFFSET_INC -> dispatchDoorOffset(entry, "inc");
            case DOOR_OFFSET_TYPE -> openDoorOffsetEntry(entry);
            case ROOM_SKY_CYCLE -> dispatchRoomSkyCycle(entry);
            case EXITS_CYCLE -> dispatchExitsCycle(entry);
            case EXIT_EVERY_DEC -> dispatchExitEvery(entry, "dec");
            case EXIT_EVERY_INC -> dispatchExitEvery(entry, "inc");
            case EXIT_EVERY_TYPE -> openExitEveryEntry(entry);
            case EXIT_MOVE_DEC -> dispatchExitMove(entry, "dec");
            case EXIT_MOVE_INC -> dispatchExitMove(entry, "inc");
            case EXIT_MOVE_TYPE -> openExitMoveEntry(entry);
            case ACTION_SAVE -> dispatchAction(entry, EditorPlotActionPacket.Action.SAVE);
            case ACTION_RESET -> dispatchAction(entry, EditorPlotActionPacket.Action.RESET);
            case ACTION_CLEAR -> dispatchAction(entry, EditorPlotActionPacket.Action.CLEAR);
            case BUTTON_CONTENTS -> openContents(entry);
            case BUTTON_ENTER_INSIDE -> dispatchAction(entry, EditorPlotActionPacket.Action.ENTER_INSIDE);
            default -> {}
        }
    }

    /**
     * Teleport the player into {@code entry}'s plot via
     * {@link EditorPlotTeleport#commandFor(String, String, String)}. Same
     * routing the type-menu's name click uses.
     */
    private static void dispatchTeleport(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.commandFor(entry.plotCategory(), entry.modelId(), entry.modelName());
        if (cmd == null) return;
        LOGGER.debug("[DungeonTrain] EditorPlotPanel teleport: {}", cmd);
        CommandRunner.run(cmd);
    }

    /**
     * Dispatch the slash-command form of {@code weight inc|dec} via the
     * shared {@link EditorPlotTeleport#weightCommandFor(String, String, String, String)}
     * routing helper.
     */
    /**
     * Open the typing field for one axis of the portal room the player is standing in.
     *
     * <p>The panel itself cannot take keyboard input, so it hands off to a keyboard menu screen —
     * the same move {@code + New} makes to reach the name picker. One axis per button, so setting
     * a width never disturbs the length someone just dialled in.</p>
     */
    private static void openAxisEntry(EditorPlotLabelsPacket.Entry entry, String axis,
                                      String label, int current) {
        if (entry.plotCategory() == null || !entry.plotCategory().hasRoomBox()) return;
        CommandMenuState.openAt(
            new games.brennan.dungeontrain.client.menu.PortalRoomAxisScreen(axis, label, current));
    }

    /** Step the portal room the player is standing in to its next mode. */
    private static void dispatchModeCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.modeCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Step the portal room the player is standing in to its next copies sub-mode. */
    private static void dispatchCopiesCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.copiesCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Set the Copies block of the portal room the player is standing in to what they are holding. */
    private static void dispatchCopiesBlockHeld(
        EditorPlotLabelsPacket.Entry entry,
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane
    ) {
        String cmd = EditorPlotTeleport.copiesBlockHeldCommandFor(entry.plotCategory(), plane);
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Open the Block Variant menu on that block, so it can be turned into a variant. */
    private static void dispatchCopiesBlockEdit(
        EditorPlotLabelsPacket.Entry entry,
        games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane
    ) {
        String cmd = EditorPlotTeleport.copiesBlockEditCommandFor(entry.plotCategory(), plane);
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Step the portal room the player is standing in to the next Contents value. */
    private static void dispatchRoomContentsCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.roomContentsCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Step the portal room the player is standing in to the next Books value. */
    private static void dispatchRoomBooksCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.roomBooksCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Step the portal room the player is standing in to the next Sky value. */
    /** Step the room's Door Wall setting: Sealed → Repeated → Sealed. */
    private static void dispatchDoorWallCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.doorWallCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    private static void dispatchRoomSkyCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.roomSkyCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Nudge how far the room's shared walkway sits off dead centre of its own width. */
    private static void dispatchDoorOffset(EditorPlotLabelsPacket.Entry entry, String dir) {
        String cmd = EditorPlotTeleport.doorOffsetCommandFor(entry.plotCategory(), dir);
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /**
     * Open the typing field for the door offset. As {@link #openExitEveryEntry}, the current value
     * comes off the room's own settings tag the panel already carries.
     */
    private static void openDoorOffsetEntry(EditorPlotLabelsPacket.Entry entry) {
        if (entry.plotCategory() == null || !entry.plotCategory().hasRoomBox()) return;
        int current = games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
            .doorOffset().value();
        CommandMenuState.openAt(new games.brennan.dungeontrain.client.menu.PortalRoomAxisScreen(
            "dooroffset", "Door Position", "blocks", current));
    }

    /**
     * Open the weights-and-band editor for the room the player is standing in.
     *
     * <p>The world panel has no typing of its own, so the Edit half of the Books row hands off to the
     * keyboard menu — the same route the number between a stepper's arrows already takes.</p>
     */
    private static void openBookMix(EditorPlotLabelsPacket.Entry entry) {
        if (entry.plotCategory() == null || !entry.plotCategory().hasRoomBox()) return;
        CommandMenuState.openAt(
            new games.brennan.dungeontrain.client.menu.PortalRoomBooksScreen(entry.roomMode()));
    }

    /** Step the portal room the player is standing in to the next Exits value. */
    private static void dispatchExitsCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.exitsCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Nudge how far apart the room's extra corridors go. */
    private static void dispatchExitEvery(EditorPlotLabelsPacket.Entry entry, String dir) {
        String cmd = EditorPlotTeleport.exitEveryCommandFor(entry.plotCategory(), dir);
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /**
     * Open the typing field for the exits spacing.
     *
     * <p>The current value comes off the room's own settings tag, which the panel already carries —
     * the same source the row's label reads, so the field cannot open showing a different number
     * from the one next to it.</p>
     */
    private static void openExitEveryEntry(EditorPlotLabelsPacket.Entry entry) {
        if (entry.plotCategory() == null || !entry.plotCategory().hasRoomBox()) return;
        int current = games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
            .exits().every();
        CommandMenuState.openAt(new games.brennan.dungeontrain.client.menu.PortalRoomAxisScreen(
            "exitevery", "Exits", "tiles", current));
    }

    /** Nudge how often the room walls off the base pair's exit. */
    private static void dispatchExitMove(EditorPlotLabelsPacket.Entry entry, String dir) {
        String cmd = EditorPlotTeleport.exitMoveCommandFor(entry.plotCategory(), dir);
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Open the typing field for the moved-exit chance, prefilled from the room's own tag. */
    private static void openExitMoveEntry(EditorPlotLabelsPacket.Entry entry) {
        if (entry.plotCategory() == null || !entry.plotCategory().hasRoomBox()) return;
        int current = games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
            .exits().moveChance();
        CommandMenuState.openAt(new games.brennan.dungeontrain.client.menu.PortalRoomAxisScreen(
            "exitmove", "Moved exit", "0-10", current));
    }

    /** Step one axis of the portal room the player is standing in. */
    private static void dispatchDimension(EditorPlotLabelsPacket.Entry entry, String axis, String dir) {
        String cmd = EditorPlotTeleport.dimensionCommandFor(entry.plotCategory(), axis, dir);
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    private static void dispatchWeight(EditorPlotLabelsPacket.Entry entry, String dir) {
        String cmd = EditorPlotTeleport.weightCommandFor(entry.plotCategory(), entry.modelId(), entry.modelName(), dir);
        if (cmd == null) return;
        LOGGER.debug("[DungeonTrain] EditorPlotPanel weight: {}", cmd);
        CommandRunner.run(cmd);
    }

    private static void dispatchAction(EditorPlotLabelsPacket.Entry entry,
                                       EditorPlotActionPacket.Action action) {
        // Only send what the server will act on. This used to guard emptiness alone, which left
        // the renderer's decision not to draw an action row as the sole thing keeping a
        // category without one from reaching a handler that would drop it.
        if (!PlotCategory.fromId(entry.category()).filter(PlotCategory::hasActionRow).isPresent()) return;
        DungeonTrainNet.sendToServer(new EditorPlotActionPacket(
            entry.category(), entry.modelId(), entry.modelName(), action));
    }

    /**
     * Open the keyboard menu drilled into the per-carriage Contents allow-list.
     * Two-step because {@link CommandMenuState#open()} sets up the anchor and
     * pushes the editor menu first; {@link CommandMenuState#drillIn(...)} then
     * stacks the contents screen on top so the user lands directly in it.
     */
    private static void openContents(EditorPlotLabelsPacket.Entry entry) {
        CarriageContentsAllowScreen screen = contentsScreenFor(entry);
        if (screen == null) return;
        CommandMenuState.open();
        CommandMenuState.drillIn(screen);
    }

    /**
     * The allow-list screen for {@code entry}, or null for a category that has no contents pool.
     *
     * <p>A carriage is addressed by its variant id ({@code modelId}); a portal room by its name
     * ({@code modelName}) — {@code modelId} is the kind tag {@code "portal_room"} and is the same
     * for every room, so using it would point every room's toggles at one shared sidecar.</p>
     */
    private static CarriageContentsAllowScreen contentsScreenFor(EditorPlotLabelsPacket.Entry entry) {
        if (entry.plotCategory() == PlotCategory.CARRIAGES) {
            return CarriageContentsAllowScreen.forCarriage(entry.modelId());
        }
        if (entry.plotCategory() == PlotCategory.PORTALS) {
            return CarriageContentsAllowScreen.forPortalRoom(entry.modelName());
        }
        return null;
    }
}
