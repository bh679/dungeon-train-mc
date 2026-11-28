package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CarriageContentsAllowScreen;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.WorldPanelInput;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.Hovered;
import games.brennan.dungeontrain.client.menu.EditorPanelFacing;
import games.brennan.dungeontrain.client.menu.EditorPanelFacingEvents;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.number.NumberField;
import games.brennan.dungeontrain.client.menu.number.NumberPad;
import games.brennan.dungeontrain.client.menu.number.NumberStepper;
import games.brennan.dungeontrain.client.menu.number.NumberTitle;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.portal.PortalRoomCopiesVariant;
import games.brennan.dungeontrain.portal.PortalRoomExits;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorPlotActionPacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
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

    private static final WorldPanelInput<Hovered> INPUT = new WorldPanelInput<>(
        EditorPlotLabelsRenderer::hovered, hit -> hit.cell() == CellKind.NONE,
        EditorPlotPanelInputHandler::shouldHandle, true, EditorPlotPanelInputHandler::dispatch);

    private EditorPlotPanelInputHandler() {}

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

    /** Cancel the world-targeted attack/use when a press lands on a panel cell. */

    /** Belt-and-braces — block any LeftClickBlock that slipped past the press cancel. */

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
        // Defer to the keyboard menu while it is open, and to the part-position
        // menu while the crosshair is on it — both have their own click handlers
        // we'd otherwise double-fire with.
        if (CommandMenuState.isOpen()) return false;
        if (PartPositionMenu.claimsPointer()) return false;
        if (games.brennan.dungeontrain.client.menu.creakingheart.CreakingHeartPanel.claimsPointer()) return false;
        return true;
    }

    /** The panel's number rows. */
    private static boolean isNumberCell(CellKind cell) {
        return switch (cell) {
            case WEIGHT_NUMBER, LENGTH_NUMBER, WIDTH_NUMBER, HEIGHT_NUMBER,
                 EXIT_EVERY_NUMBER, EXIT_MOVE_NUMBER, COPIES_FLOOR_HEIGHT_NUMBER,
                 FRAME_MIN_LEVEL_NUMBER, FRAME_MAX_LEVEL_NUMBER,
                 FRAME_DOOR_DISTANCE_NUMBER, DOOR_DISTANCE_NUMBER -> true;
            default -> false;
        };
    }

    private static void dispatch(Hovered hit, boolean shift, int button) {
        // Right-click is a number cell's step-down and nothing else.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !isNumberCell(hit.cell())) return;
        List<EditorPlotLabelsPacket.Entry> entries = EditorPlotLabelsRenderer.entries();
        if (hit.entryIndex() < 0 || hit.entryIndex() >= entries.size()) return;
        EditorPlotLabelsPacket.Entry entry = entries.get(hit.entryIndex());

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        switch (hit.cell()) {
            case NAME -> dispatchTeleport(entry);
            case FACE -> {
                BlockPos pos = entry.worldPos();
                EditorPanelFacingEvents.onButton(pos,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                    EditorPanelFacing.plotPanel());
            }
            // Number rows: click steps up, shift- or right-click steps down, cmd-click types.
            case WEIGHT_NUMBER, LENGTH_NUMBER, WIDTH_NUMBER, HEIGHT_NUMBER,
                 EXIT_EVERY_NUMBER, EXIT_MOVE_NUMBER, COPIES_FLOOR_HEIGHT_NUMBER,
                 FRAME_MIN_LEVEL_NUMBER, FRAME_MAX_LEVEL_NUMBER,
                 FRAME_DOOR_DISTANCE_NUMBER, DOOR_DISTANCE_NUMBER -> {
                NumberField field = rowNumber(hit.cell(), entry);
                if (field != null) {
                    NumberStepper.click(field, button, shift, MenuClickModifiers.cmdDown(), NumberPad.returningTo(null));
                }
            }
            case MODE_CYCLE -> dispatchModeCycle(entry);
            case LOCK_HELD -> dispatchLockHeld(entry);
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
            case GROUND_CYCLE -> dispatchGroundCycle(entry);
            case GROUND_BELOW_TOGGLE -> dispatchGroundBelowToggle(entry);
            case DOOR_WALL_CYCLE -> dispatchDoorWallCycle(entry);
            case ROOM_SKY_CYCLE -> dispatchRoomSkyCycle(entry);
            case ROOM_FOG_CYCLE -> dispatchRoomFogCycle(entry);
            case ROOM_DRIFT_CYCLE -> dispatchRoomDriftCycle(entry);
            case ENTRANCE_CYCLE -> dispatchEntranceCycle(entry);
            case EXITS_CYCLE -> dispatchExitsCycle(entry);
            case ACTION_SAVE -> dispatchAction(entry, EditorPlotActionPacket.Action.SAVE);
            case ACTION_RESET -> dispatchAction(entry, EditorPlotActionPacket.Action.RESET);
            case ACTION_CLEAR -> dispatchAction(entry, EditorPlotActionPacket.Action.CLEAR);
            case BUTTON_CONTENTS -> openContents(entry);
            case BUTTON_ENTER_INSIDE -> dispatchAction(entry, EditorPlotActionPacket.Action.ENTER_INSIDE);
            case FRAME_GROUND_HELD -> runFrame(entry, "ground", "held");
            case FRAME_GROUND_EDIT -> runFrame(entry, "ground", "edit");
            case FRAME_DIM_BEDROCK, FRAME_DIM_VOID, FRAME_DIM_WORLD_BORDER, FRAME_DIM_ENDLESS_OPEN,
                 FRAME_DIM_PLANK_BRIDGE -> runFrame(entry,
                "dimension", EditorPlotLabelsRenderer.frameDimensionToken(hit.cell()));
            case FRAME_PHASES -> openDrillIn(new games.brennan.dungeontrain.client.menu.PhaseSelectScreen(
                PlotCategory.CHUNK_FRAMES, entry.modelId(), entry.modelName()));
            case FRAME_STAGE -> openDrillIn(new games.brennan.dungeontrain.client.menu.StagePickerScreen(
                PlotCategory.CHUNK_FRAMES, entry.modelId(), entry.modelName(),
                EditorPlotLabelsRenderer.statusIsFor(entry) ? EditorStatusHudOverlay.stageId() : ""));
            case FRAME_ROOMS_ALL -> runFrame(entry, "rooms", "all");
            case FRAME_ROOMS_NONE -> runFrame(entry, "rooms", "none");
            case FRAME_ROOM_TOGGLE -> dispatchFrameRoomToggle(entry, hit.row());
            case FRAME_SAVE -> {
                if (EditorPlotLabelsRenderer.isFramePanel(entry)) {
                    CommandRunner.run(games.brennan.dungeontrain.client.menu.EditorMenuScreen.CHUNK_FRAME_SAVE_COMMAND);
                }
            }
            default -> {}
        }
    }

    /** The chunk frame command root; the frame panel's own rows have no {@link PlotCategory.Verb}. */
    private static final String CHUNK_FRAME_COMMAND = "dungeontrain editor chunkframe";

    /** {@code dungeontrain editor chunkframe <sub> <frame> <arg>} — nothing for a non-frame entry. */
    private static void runFrame(EditorPlotLabelsPacket.Entry entry, String sub, String arg) {
        if (!EditorPlotLabelsRenderer.isFramePanel(entry) || arg == null || entry.modelName().isEmpty()) return;
        CommandRunner.run(CHUNK_FRAME_COMMAND + " " + sub + " " + entry.modelName() + " " + arg);
    }

    /** Flip the chunk room on row {@code row} of a frame panel: on → off, off → on. */
    private static void dispatchFrameRoomToggle(EditorPlotLabelsPacket.Entry entry, int row) {
        String room = EditorPlotLabelsRenderer.frameRoomAt(entry, row);
        if (room == null) return;
        boolean on = EditorPlotLabelsRenderer.frameSnapshot(entry)
            .map(s -> s.selected().contains(room)).orElse(false);
        runFrame(entry, "room", room + " " + (on ? "off" : "on"));
    }

    /**
     * Open the keyboard menu drilled into {@code screen} — the same two steps {@link #openContents}
     * takes, so Back returns to the editor menu.
     */
    private static void openDrillIn(games.brennan.dungeontrain.client.menu.MenuScreen screen) {
        CommandMenuState.open();
        CommandMenuState.drillIn(screen);
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

    /** Step the portal room the player is standing in to its next mode. */
    private static void dispatchModeCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.modeCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Write the shell of the portal room the player is standing in in whatever they hold. */
    private static void dispatchLockHeld(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.lockHeldCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    /** Step the portal room the player is standing in to its next copies sub-mode. */
    private static void dispatchCopiesCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.copiesCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

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

    private static void dispatchGroundCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.groundCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    private static void dispatchGroundBelowToggle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.groundBelowCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    private static void dispatchRoomFogCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.roomFogCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    private static void dispatchEntranceCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.entranceCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
    }

    private static void dispatchRoomDriftCycle(EditorPlotLabelsPacket.Entry entry) {
        String cmd = EditorPlotTeleport.roomDriftCycleCommandFor(entry.plotCategory());
        if (cmd == null) return;
        CommandRunner.run(cmd);
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

    /**
     * The number behind one of the panel's number rows, or null when the row has none to edit
     * (no weight pool, or not a portal room). Every command here takes {@code inc}, {@code dec} or
     * an outright value in the same slot, so stepping and typing differ only in the token.
     *
     * <p>Room sizes offer the parser's loosest window; {@code PortalRoomLayout.clampSize} on the
     * server is what holds a typed size inside this world's floors and ceilings, visibly.</p>
     */
    private static NumberField rowNumber(CellKind cell, EditorPlotLabelsPacket.Entry entry) {
        PlotCategory category = entry.plotCategory();
        return switch (cell) {
            case WEIGHT_NUMBER -> entry.weight() == EditorPlotLabelsPacket.NO_WEIGHT
                || EditorPlotTeleport.weightCommandFor(category, entry.modelId(), entry.modelName(), "inc") == null
                ? null
                : NumberField.ofTokens(NumberTitle.of("weight"), entry.weight(),
                    // A frame's weight floors at 1 (ChunkFrameMeta); every other pool at 0.
                    category == PlotCategory.CHUNK_FRAMES
                        ? games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta.MIN_WEIGHT : 0,
                    category == PlotCategory.CHUNK_FRAMES
                        ? games.brennan.dungeontrain.portal.chunkframe.ChunkFrameMeta.MAX_WEIGHT : 100, false,
                    token -> EditorPlotTeleport.weightCommandFor(category, entry.modelId(), entry.modelName(), token));
            case LENGTH_NUMBER -> dimension(category, "length", "length", entry.roomLength());
            case WIDTH_NUMBER -> dimension(category, "width", "width", entry.roomWidth());
            case HEIGHT_NUMBER -> dimension(category, "height", "height", entry.roomHeight());
            case EXIT_EVERY_NUMBER -> roomNumber(category, NumberTitle.of("exit_every"),
                PortalRoomSettings.parse(entry.roomMode()).exits().every(),
                PortalRoomExits.MIN_EVERY, PortalRoomExits.MAX_EVERY,
                token -> EditorPlotTeleport.exitEveryCommandFor(category, token));
            case EXIT_MOVE_NUMBER -> roomNumber(category, NumberTitle.of("exit_move"),
                PortalRoomSettings.parse(entry.roomMode()).exits().moveChance(),
                PortalRoomExits.MOVE_NEVER, PortalRoomExits.MOVE_ALWAYS,
                token -> EditorPlotTeleport.exitMoveCommandFor(category, token));
            case COPIES_FLOOR_HEIGHT_NUMBER -> roomNumber(category, NumberTitle.of("floor_height"),
                entry.copiesFloorHeight(),
                PortalRoomCopiesVariant.MIN_FLOOR_HEIGHT, PortalRoomCopiesVariant.MAX_FLOOR_HEIGHT,
                token -> EditorPlotTeleport.copiesFloorHeightCommandFor(category, token));
            case DOOR_DISTANCE_NUMBER -> roomNumber(category, NumberTitle.of("door_distance"),
                PortalRoomSettings.parse(entry.roomMode()).doorDistance().value(),
                0, games.brennan.dungeontrain.portal.PortalRoomDoorDistance.MAX,
                token -> EditorPlotTeleport.doorDistanceCommandFor(category, token));
            case FRAME_DOOR_DISTANCE_NUMBER -> frameDoorDistance(entry);
            case FRAME_MIN_LEVEL_NUMBER -> frameLevel(entry, "minlevel");
            case FRAME_MAX_LEVEL_NUMBER -> frameLevel(entry, "maxlevel");
            default -> null;
        };
    }

    /**
     * A frame's level bound, edited as the keyboard menu's Min Lv / Max Lv rows are
     * ({@code EditorMenuScreen.levelNumber}): min runs 0..MAX_LEVEL; max runs from the min up and a
     * blank value is "all" ({@code -1}). Null until the status overlay reports this frame, so a step
     * never starts from another plot's numbers.
     */
    /** An Endless open frame's door distance, stepped or typed like the weight. */
    private static NumberField frameDoorDistance(EditorPlotLabelsPacket.Entry entry) {
        var snap = EditorPlotLabelsRenderer.frameSnapshot(entry);
        if (snap.isEmpty() || entry.modelName().isEmpty()) return null;
        String prefix = CHUNK_FRAME_COMMAND + " doordistance " + entry.modelName();
        return NumberField.ofTokens(NumberTitle.of("door_distance"), snap.get().doorDistance(), 0,
            games.brennan.dungeontrain.portal.PortalRoomDoorDistance.MAX, false, token -> prefix + " " + token);
    }

    private static NumberField frameLevel(EditorPlotLabelsPacket.Entry entry, String sub) {
        if (!EditorPlotLabelsRenderer.isFramePanel(entry) || !EditorPlotLabelsRenderer.statusIsFor(entry)) return null;
        String prefix = PlotCategory.CHUNK_FRAMES.levelCommand(sub, entry.modelId(), entry.modelName()).orElse(null);
        if (prefix == null) return null;
        int max = games.brennan.dungeontrain.template.TemplateGate.MAX_LEVEL;
        int minLv = Math.max(0, EditorStatusHudOverlay.minLevel());
        java.util.function.Function<String, String> commandFor = token -> prefix + " " + token;
        if ("maxlevel".equals(sub)) {
            int maxLv = EditorStatusHudOverlay.maxLevel();
            return NumberField.ofTokens(NumberTitle.of("max_level"), maxLv < 0 ? NumberField.ALL : maxLv,
                minLv, max, true, commandFor);
        }
        return NumberField.ofTokens(NumberTitle.of("min_level"), minLv, 0, max, false, commandFor);
    }

    private static NumberField dimension(PlotCategory category, String axis, String title, int value) {
        return roomNumber(category, NumberTitle.of(title), value,
            Math.min(PortalRoomLayout.MIN_LENGTH, PortalRoomLayout.MIN_HEIGHT),
            Math.max(PortalRoomLayout.MAX_LENGTH, Math.max(PortalRoomLayout.MAX_WIDTH, PortalRoomLayout.MAX_HEIGHT)),
            token -> EditorPlotTeleport.dimensionCommandFor(category, axis, token));
    }

    /** A setting of the portal room the player stands in; null outside one. */
    private static NumberField roomNumber(PlotCategory category, String title, int value, int min, int max,
                                          java.util.function.Function<String, String> commandFor) {
        if (category == null || !category.hasRoomBox()) return null;
        return NumberField.ofTokens(title, value, min, max, false, commandFor);
    }

    private static void dispatchAction(EditorPlotLabelsPacket.Entry entry,
                                       EditorPlotActionPacket.Action action) {
        // Only send what the server will act on. This used to guard emptiness alone, which left
        // the renderer's decision not to draw an action row as the sole thing keeping a
        // category without one from reaching a handler that would drop it.
        if (!PlotCategory.fromId(entry.category()).filter(PlotCategory::hasActionRow).isPresent()) return;
        // Enter lands in the doorway; shift at the click asks for the centre instead. Read here, at
        // the click, so the modifier means the one the author was holding when they pressed.
        boolean centre = action == EditorPlotActionPacket.Action.ENTER_INSIDE
            && net.minecraft.client.gui.screens.Screen.hasShiftDown();
        DungeonTrainNet.sendToServer(new EditorPlotActionPacket(
            entry.category(), entry.modelId(), entry.modelName(), action, centre));
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
