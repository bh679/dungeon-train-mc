package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.NewSourcePickerScreen;
import games.brennan.dungeontrain.client.menu.parts.PartPositionMenu;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.Hovered;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
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
 * Mouse wiring for the floating template-type menus. Mirrors
 * {@link EditorPlotPanelInputHandler} — press-arm at attack-cancel time,
 * release dispatches the action, defers when the keyboard menu or part
 * menu owns input.
 *
 * <p>Cell-specific dispatch:
 * <ul>
 *   <li>Name cell click → teleport via
 *       {@link EditorPlotTeleport#commandFor(String, String, String)}.</li>
 *   <li>Weight cell click → weight {@code +1} (or {@code -1} on shift)
 *       via {@link EditorPlotTeleport#weightCommandFor(String, String, String, String)}.</li>
 * </ul></p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorTypeMenuInputHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** True only when a press fired while a menu cell was hovered and we're awaiting its release. */
    private static boolean pressArmed;
    /** Captured at press time so the release knows whether it was a shift-click. */
    private static boolean pressShift;

    private EditorTypeMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        Hovered hit = EditorTypeMenuRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        pressArmed = true;
        Minecraft mc = Minecraft.getInstance();
        long win = mc.getWindow().getWindow();
        pressShift = mc.screen == null
            && (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
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
        boolean shift = pressShift || (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;

        Hovered hit = EditorTypeMenuRenderer.hovered();
        if (hit.cell() == CellKind.NONE) return;
        dispatch(hit, shift);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        if (EditorTypeMenuRenderer.hovered().cell() == CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!shouldHandle()) return;
        if (Minecraft.getInstance().screen == null) {
            EditorTypeMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (EditorTypeMenuRenderer.menus().isEmpty()) return false;
        if (CommandMenuState.isOpen()) return false;
        if (PartPositionMenu.isActive()) return false;
        return true;
    }

    private static void dispatch(Hovered hit, boolean shift) {
        List<EditorTypeMenusPacket.Menu> menus = EditorTypeMenuRenderer.menus();
        if (hit.menuIdx() < 0 || hit.menuIdx() >= menus.size()) return;
        EditorTypeMenusPacket.Menu menu = menus.get(hit.menuIdx());

        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }

        // Header click → teleport to the first variant in the menu (the one
        // closest to the menu's row-start anchor, useful as a "go to start
        // of this row" shortcut).
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.HEADER) {
            if (menu.variants().isEmpty()) return;
            EditorTypeMenusPacket.Variant first = menu.variants().get(0);
            String cmd = EditorPlotTeleport.commandFor(
                first.category(), first.modelId(), first.modelName());
            if (cmd == null) return;
            LOGGER.debug("[DungeonTrain] EditorTypeMenu header teleport (first variant '{}'): {}",
                first.name(), cmd);
            CommandRunner.run(cmd);
            return;
        }

        // "+ New" footer click → open the keyboard menu's source picker /
        // typing prompt for this menu's category. variantIdx is -1 for NEW
        // hits so the variant lookup below would short-circuit; handle here
        // before that branch.
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.NEW) {
            LOGGER.debug("[DungeonTrain] EditorTypeMenu New: category '{}'",
                menu.variants().isEmpty() ? "<empty>" : menu.variants().get(0).category());
            dispatchNew(menu);
            return;
        }

        if (hit.variantIdx() < 0 || hit.variantIdx() >= menu.variants().size()) return;
        EditorTypeMenusPacket.Variant variant = menu.variants().get(hit.variantIdx());

        switch (hit.cell()) {
            case NAME -> {
                String cmd = EditorPlotTeleport.commandFor(
                    variant.category(), variant.modelId(), variant.modelName());
                if (cmd == null) return;
                LOGGER.debug("[DungeonTrain] EditorTypeMenu teleport: {}", cmd);
                CommandRunner.run(cmd);
            }
            case WEIGHT -> {
                String dir = shift ? "dec" : "inc";
                String cmd = EditorPlotTeleport.weightCommandFor(
                    variant.category(), variant.modelId(), variant.modelName(), dir);
                if (cmd == null) return;
                LOGGER.debug("[DungeonTrain] EditorTypeMenu weight: {}", cmd);
                CommandRunner.run(cmd);
            }
            default -> {}
        }
    }

    /**
     * Open the keyboard worldspace menu and drill / type into the matching
     * "new variant" entry point for this menu's category. Mirrors what the
     * keyboard menu's {@code EditorMenuScreen} "New" button does, so the
     * floating panel and the keyboard menu share authoring flows.
     *
     * <p>Categories are read from {@code variants.get(0).category()} since
     * every variant in a single floating menu shares its category.</p>
     */
    private static void dispatchNew(EditorTypeMenusPacket.Menu menu) {
        if (menu.variants().isEmpty()) return;
        EditorTypeMenusPacket.Variant first = menu.variants().get(0);
        String category = first.category();
        // Prefer the player's currently active model id (the one tinted green
        // in the floating menu) so "Current (<id>)" in the picker clones from
        // the variant the player is standing in. Fall back to the first row.
        String activeId = EditorStatusHudOverlay.modelId();
        String currentId = (activeId != null && !activeId.isEmpty())
            ? activeId
            : first.modelId();

        CommandMenuState.open();
        switch (category) {
            case "CARRIAGES" -> CommandMenuState.drillIn(
                new NewSourcePickerScreen(
                    NewSourcePickerScreen.Category.CARRIAGES, null, currentId));
            case "CONTENTS" -> CommandMenuState.drillIn(
                new NewSourcePickerScreen(
                    NewSourcePickerScreen.Category.CONTENTS, null, currentId));
            case "PARTS" -> {
                // For PARTS the modelId is the kind tag (floor / walls / roof /
                // doors); the picker's "Current" option is gated on a non-empty
                // currentId, which we don't have a stable source for from a
                // parts menu (the variant rows don't represent the player's
                // current part variant), so leave currentId blank.
                CommandMenuState.drillIn(
                    new NewSourcePickerScreen(
                        NewSourcePickerScreen.Category.PARTS, first.modelId(), ""));
            }
            case "TRACKS" -> {
                // Tracks have no source picker — drop straight into typing
                // mode against the same prefix EditorMenuScreen.newEntryFor
                // builds. The track-side modelId is already the kind tag the
                // server's parser expects (see EditorPlotTeleport).
                CommandMenuState.beginTyping(
                    "name",
                    "dungeontrain editor tracks new " + first.modelId(),
                    "");
            }
            default -> {
                LOGGER.warn("[DungeonTrain] EditorTypeMenu New: unsupported category '{}'", category);
            }
        }
    }
}
