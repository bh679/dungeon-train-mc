package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CarriageContentsAllowScreen;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer.Hovered;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
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
        String cmd = EditorPlotTeleport.commandFor(entry.category(), entry.modelId(), entry.modelName());
        if (cmd == null) return;
        LOGGER.debug("[DungeonTrain] EditorPlotPanel teleport: {}", cmd);
        CommandRunner.run(cmd);
    }

    /**
     * Dispatch the slash-command form of {@code weight inc|dec} via the
     * shared {@link EditorPlotTeleport#weightCommandFor(String, String, String, String)}
     * routing helper.
     */
    private static void dispatchWeight(EditorPlotLabelsPacket.Entry entry, String dir) {
        String cmd = EditorPlotTeleport.weightCommandFor(entry.category(), entry.modelId(), entry.modelName(), dir);
        if (cmd == null) return;
        LOGGER.debug("[DungeonTrain] EditorPlotPanel weight: {}", cmd);
        CommandRunner.run(cmd);
    }

    private static void dispatchAction(EditorPlotLabelsPacket.Entry entry,
                                       EditorPlotActionPacket.Action action) {
        if (entry.category().isEmpty()) return;
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
        if (!"CARRIAGES".equals(entry.category())) return;
        CommandMenuState.open();
        CommandMenuState.drillIn(new CarriageContentsAllowScreen(entry.modelId()));
    }
}
