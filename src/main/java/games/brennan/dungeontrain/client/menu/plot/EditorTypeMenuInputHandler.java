package games.brennan.dungeontrain.client.menu.plot;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PackageListClient;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.NewSourcePickerScreen;
import games.brennan.dungeontrain.client.menu.PackageListScreen;
import games.brennan.dungeontrain.client.menu.PackageMenuActions;
import games.brennan.dungeontrain.net.PackageListSyncPacket;
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

        // Stages management panel — toolbar (Add / Remove) + inline-editable stage rows.
        if (menu.isStagesMenu()) {
            handleStagesMenu(hit, menu, shift);
            return;
        }

        // Category bar click → run "dt editor <id>" (CommandRunner expects
        // bare command text, no leading slash — the chat path adds it).
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.CATEGORY) {
            int slot = hit.slotIdx();
            if (slot < 0 || slot >= menu.categoryBar().size()) return;
            EditorTypeMenusPacket.CategoryButton btn = menu.categoryBar().get(slot);
            String cmd = "dt editor " + btn.id();
            LOGGER.debug("[DungeonTrain] EditorTypeMenu category click: {}", cmd);
            CommandRunner.run(cmd);
            return;
        }

        // Package menu top-row cells — independent of any variant index.
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.PKG_RELOAD) {
            LOGGER.debug("[DungeonTrain] EditorTypeMenu pkg Reload click");
            CommandRunner.run("dungeontrain editor import");
            PackageListClient.scheduleRefreshAfterAction();
            return;
        }
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.PKG_OPEN_FOLDER) {
            LOGGER.debug("[DungeonTrain] EditorTypeMenu pkg Open Packages click");
            PackageMenuActions.openDtpacksFolder();
            return;
        }

        // Per-package row cells — variantIdx is the entry index in PackageListClient.entries().
        EditorTypeMenuRenderer.CellKind c = hit.cell();
        if (c == EditorTypeMenuRenderer.CellKind.PKG_NAME
            || c == EditorTypeMenuRenderer.CellKind.PKG_SAVE
            || c == EditorTypeMenuRenderer.CellKind.PKG_OPEN
            || c == EditorTypeMenuRenderer.CellKind.PKG_ENABLE) {
            java.util.List<PackageListSyncPacket.Entry> entries = PackageListClient.entries();
            int idx = hit.variantIdx();
            if (idx < 0 || idx >= entries.size()) return;
            PackageListSyncPacket.Entry entry = entries.get(idx);
            switch (c) {
                case PKG_NAME -> {
                    String cmd = "dungeontrain package activate " + entry.name();
                    LOGGER.debug("[DungeonTrain] EditorTypeMenu pkg activate: {}", cmd);
                    CommandRunner.run(cmd);
                    PackageListClient.scheduleRefreshAfterAction();
                }
                case PKG_SAVE -> {
                    // Fall through to the X-menu drilled at the package list so
                    // the user types the new name there — no parallel typing
                    // buffer needed. Same idiom dispatchNew uses.
                    LOGGER.debug("[DungeonTrain] EditorTypeMenu pkg Save click — opening PackageListScreen");
                    CommandMenuState.openAt(new PackageListScreen());
                }
                case PKG_OPEN -> {
                    LOGGER.debug("[DungeonTrain] EditorTypeMenu pkg Open: {}", entry.name());
                    PackageMenuActions.openWorkingFolder(entry.name());
                }
                case PKG_ENABLE -> {
                    String cmd = "dungeontrain package "
                        + (entry.enabled() ? "disable " : "enable ")
                        + entry.name();
                    LOGGER.debug("[DungeonTrain] EditorTypeMenu pkg toggle enable: {}", cmd);
                    CommandRunner.run(cmd);
                    PackageListClient.scheduleRefreshAfterAction();
                }
                default -> {}
            }
            return;
        }

        // Type-tab click → teleport to the first variant of that type.
        // Collapsed tabs jump to a different row; clicking the expanded tab
        // repeats the current row's first-variant teleport (harmless, same
        // behaviour as the legacy HEADER click).
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.TYPE_TAB) {
            int slot = hit.slotIdx();
            if (slot < 0 || slot >= menu.typeStrip().size()) return;
            EditorTypeMenusPacket.TypeTab tab = menu.typeStrip().get(slot);
            String cmd = EditorPlotTeleport.commandFor(
                tab.category(), tab.modelId(), tab.modelName());
            if (cmd == null) return;
            LOGGER.debug("[DungeonTrain] EditorTypeMenu type-tab teleport ({}): {}",
                tab.typeName(), cmd);
            CommandRunner.run(cmd);
            return;
        }

        // Sub-variant cell click → teleport to that sub-variant. Cells
        // live on a specific variant row, so variantIdx picks the parent
        // and slotIdx picks the child inside {@code variant.subVariants()}.
        if (hit.cell() == EditorTypeMenuRenderer.CellKind.SUB_VARIANT) {
            if (hit.variantIdx() < 0 || hit.variantIdx() >= menu.variants().size()) return;
            EditorTypeMenusPacket.Variant parent = menu.variants().get(hit.variantIdx());
            int slot = hit.slotIdx();
            if (slot < 0 || slot >= parent.subVariants().size()) return;
            EditorTypeMenusPacket.Variant child = parent.subVariants().get(slot);
            String cmd = EditorPlotTeleport.commandFor(
                child.category(), child.modelId(), child.modelName());
            if (cmd == null) return;
            LOGGER.debug("[DungeonTrain] EditorTypeMenu sub-variant teleport ({}): {}",
                child.name(), cmd);
            CommandRunner.run(cmd);
            return;
        }

        // Header click → teleport to the first variant in the menu (the one
        // closest to the menu's row-start anchor, useful as a "go to start
        // of this row" shortcut). Only emitted by companion menus now; nav
        // menus replace this with the expanded TYPE_TAB.
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
                // Shift-click a gateable template's name opens the Stage / Custom picker (the
                // floating-menu entry point for linking a template to a Stage). Plain click teleports.
                // Skipped on Sub-Variants rows — they have a dedicated STAGE cell, and the shortcut
                // would misfire on the weight-only "(default)" self-row (not a gateable member).
                if (shift && !isSubVariants(menu) && EditorPlotTeleport.stageApplyCommandFor(
                        variant.category(), variant.modelId(), variant.modelName(), "custom") != null) {
                    openStagePicker(menu, variant);
                    return;
                }
                String cmd = EditorPlotTeleport.commandFor(
                    variant.category(), variant.modelId(), variant.modelName());
                if (cmd == null) return;
                LOGGER.debug("[DungeonTrain] EditorTypeMenu teleport: {}", cmd);
                CommandRunner.run(cmd);
            }
            // Dedicated per-part visibility checkbox cell (its own leading column on PARTS rows) — a
            // plain click toggles that part's editor-grid visibility. Replaces the old shift-click-
            // the-name shortcut; the hit-test only yields PART_VISIBLE for PARTS rows.
            case PART_VISIBLE -> {
                String cmd = "dungeontrain editor part display "
                    + variant.modelId() + " " + variant.modelName() + " toggle";
                LOGGER.debug("[DungeonTrain] EditorTypeMenu part-visible: {}", cmd);
                CommandRunner.run(cmd);
            }
            case WEIGHT -> {
                String dir = shift ? "dec" : "inc";
                // Sub-variants companion menu: weight cells reference per-member
                // weights (and the parent's editable selfWeight on row 0) inside
                // the parent's .group.json sidecar — route to the group-member
                // weight command, not the top-level contents weight pool (which
                // the spawn pipeline ignores for members). The same command
                // path handles the (default) row by passing parent == child;
                // the server interprets that as a selfWeight edit.
                boolean isSubVariants = games.brennan.dungeontrain.editor.VariantOverlayRenderer.SUB_VARIANTS_TYPE_NAME
                    .equals(menu.typeName());
                if (isSubVariants) {
                    String parentId = menu.variants().get(0).modelId();
                    String memberId = variant.modelId();
                    String cmd = EditorPlotTeleport.groupMemberWeightCommandFor(parentId, memberId, dir);
                    LOGGER.debug("[DungeonTrain] EditorTypeMenu weight (group {}): {}",
                        parentId.equals(memberId) ? "self" : "member", cmd);
                    CommandRunner.run(cmd);
                    return;
                }
                String cmd = EditorPlotTeleport.weightCommandFor(
                    variant.category(), variant.modelId(), variant.modelName(), dir);
                if (cmd == null) return;
                LOGGER.debug("[DungeonTrain] EditorTypeMenu weight: {}", cmd);
                CommandRunner.run(cmd);
            }
            // Stage selector cell → open the Stage / Custom picker (chip when linked, ◆? when Custom).
            case STAGE -> openStagePicker(menu, variant);
            // Gate cells: while the row is Stage-linked its cells show the Stage's (read-only) gate,
            // so a click opens the picker (re-pick / Custom) instead of editing — which would
            // silently detach. Custom rows edit the inline gate as before.
            case MIN_LEVEL -> { if (!openPickerIfLinked(menu, variant)) dispatchLevel(menu, variant, "minlevel", shift); }
            case MAX_LEVEL -> { if (!openPickerIfLinked(menu, variant)) dispatchLevel(menu, variant, "maxlevel", shift); }
            case PHASE -> { if (!openPickerIfLinked(menu, variant)) dispatchPhase(menu, variant, hit.slotIdx(), shift); }
            default -> {}
        }
    }

    /** Open the Stage / Custom picker for {@code variant} when it is Stage-linked; returns whether it did. */
    private static boolean openPickerIfLinked(EditorTypeMenusPacket.Menu menu, EditorTypeMenusPacket.Variant variant) {
        if (!variant.isStageLinked()) return false;
        openStagePicker(menu, variant);
        return true;
    }

    /**
     * Open the {@link games.brennan.dungeontrain.client.menu.StagePickerScreen} for {@code variant}.
     * Sub-Variants companion rows target the contents <em>group member</em> picker (parent = the
     * companion's first/"default" row); all other rows use the top-level (category, model) picker.
     */
    private static void openStagePicker(EditorTypeMenusPacket.Menu menu, EditorTypeMenusPacket.Variant variant) {
        if (isSubVariants(menu)) {
            String parentId = menu.variants().get(0).modelId();
            CommandMenuState.openAt(games.brennan.dungeontrain.client.menu.StagePickerScreen
                .forGroupMember(parentId, variant.modelId(), variant.stageIds()));
            return;
        }
        CommandMenuState.openAt(new games.brennan.dungeontrain.client.menu.StagePickerScreen(
            variant.category(), variant.modelId(), variant.modelName(), variant.primaryStageId()));
    }

    /** Lowercase phase tokens for the Stages panel's inline dimension cells (TrainPhase ordinal order). */
    private static final String[] STAGE_PHASE_TOKENS = {"overworld", "nether", "void", "end"};

    /**
     * Click routing for the world-space Stages panel:
     * <ul>
     *   <li>{@code + Add} → opens the Stages window (with its create-stage typing row).</li>
     *   <li>{@code – Remove} → toggles remove-mode; a stage-row click then deletes that stage.</li>
     *   <li>A stage row's {@code ≥ / ≤ / O N V E} cells edit that stage's gate live (inline).</li>
     *   <li>A stage row's name → its edit screen (or deletes it while remove-mode is on).</li>
     * </ul>
     */
    private static void handleStagesMenu(Hovered hit, EditorTypeMenusPacket.Menu menu, boolean shift) {
        switch (hit.cell()) {
            case STAGE_ADD, HEADER -> CommandMenuState.openAt(
                new games.brennan.dungeontrain.client.menu.StagesListScreen());
            case STAGE_REMOVE -> EditorTypeMenuRenderer.toggleStagesRemoveMode();
            case NAME -> {
                String id = stageIdAt(menu, hit);
                if (id == null) return;
                if (EditorTypeMenuRenderer.isStagesRemoveMode()) {
                    CommandRunner.run("dungeontrain editor stage delete " + id);
                    EditorTypeMenuRenderer.clearStagesRemoveMode();
                } else {
                    // Toggle this stage as the focused per-stage carriage preview (re-click deselects).
                    // The gate is edited inline on this same row (≥ / ≤ / phase cells), so the old
                    // StageEditScreen drill-in is redundant — clicking the name now selects.
                    CommandRunner.run("dungeontrain editor stage select " + id);
                }
            }
            case MIN_LEVEL -> {
                String id = stageIdAt(menu, hit);
                if (id != null) CommandRunner.run(EditorPlotTeleport.stageLevelCommandFor(
                    id, "minlevel", shift ? "dec" : "inc"));
            }
            case MAX_LEVEL -> {
                String id = stageIdAt(menu, hit);
                if (id != null) CommandRunner.run(EditorPlotTeleport.stageLevelCommandFor(
                    id, "maxlevel", shift ? "dec" : "inc"));
            }
            case PHASE -> {
                String id = stageIdAt(menu, hit);
                int slot = hit.slotIdx();
                if (id == null || slot < 0 || slot >= STAGE_PHASE_TOKENS.length) return;
                int mask = menu.variants().get(hit.variantIdx()).phaseMask();
                boolean on = (mask & (1 << slot)) != 0;
                String action = shift ? "others" : (on ? "off" : "on");
                CommandRunner.run(EditorPlotTeleport.stagePhaseCommandFor(id, STAGE_PHASE_TOKENS[slot], action));
            }
            case STAGE_BLOCKS -> {
                // The row's icon strip just SELECTS the stage (which auto-opens/closes its panel) —
                // it is no longer an independent panel open/close. Same select-toggle as the name
                // cell. Inert while remove-mode is armed so a mis-click can't act mid-delete.
                if (EditorTypeMenuRenderer.isStagesRemoveMode()) return;
                String id = stageIdAt(menu, hit);
                if (id != null) CommandRunner.run("dungeontrain editor stage select " + id);
            }
            default -> { }
        }
    }

    /** The stage id of the row {@code hit} points at, or null when out of range. */
    private static String stageIdAt(EditorTypeMenusPacket.Menu menu, Hovered hit) {
        if (hit.variantIdx() < 0 || hit.variantIdx() >= menu.variants().size()) return null;
        return menu.variants().get(hit.variantIdx()).modelId();
    }

    /** Lowercase phase tokens indexed by {@code TrainPhase} ordinal (OVERWORLD/NETHER/VOID/END). */
    private static final String[] PHASE_TOKENS = {"overworld", "nether", "void", "end"};

    /**
     * Bump a per-template gate level bound. On Sub-Variants rows this targets the group member's
     * gate (in the parent's {@code .group.json}); elsewhere the top-level template gate.
     */
    private static void dispatchLevel(EditorTypeMenusPacket.Menu menu, EditorTypeMenusPacket.Variant variant,
                                      String sub, boolean shift) {
        String dir = shift ? "dec" : "inc";
        if (isSubVariants(menu)) {
            String parentId = menu.variants().get(0).modelId();
            String cmd = EditorPlotTeleport.groupMemberLevelCommandFor(parentId, variant.modelId(), sub, dir);
            LOGGER.debug("[DungeonTrain] EditorTypeMenu group {} : {}", sub, cmd);
            CommandRunner.run(cmd);
            return;
        }
        String cmd = EditorPlotTeleport.levelCommandFor(
            variant.category(), variant.modelId(), variant.modelName(), sub, dir);
        if (cmd == null) return;
        LOGGER.debug("[DungeonTrain] EditorTypeMenu {} : {}", sub, cmd);
        CommandRunner.run(cmd);
    }

    /**
     * Toggle the dimension identified by {@code slot} (TrainPhase ordinal) on the variant's gate.
     * Plain click flips that one ({@code on}/{@code off}); shift-click sends the shared {@code others}
     * action — "toggle all but that one" — so this matches the parts menu and the keyboard Phases menu.
     */
    private static void dispatchPhase(EditorTypeMenusPacket.Menu menu, EditorTypeMenusPacket.Variant variant,
                                      int slot, boolean shift) {
        if (slot < 0 || slot >= PHASE_TOKENS.length) return;
        boolean on = (variant.phaseMask() & (1 << slot)) != 0;
        String action = shift ? "others" : (on ? "off" : "on");
        if (isSubVariants(menu)) {
            String parentId = menu.variants().get(0).modelId();
            String cmd = EditorPlotTeleport.groupMemberPhaseCommandFor(
                parentId, variant.modelId(), PHASE_TOKENS[slot], action);
            LOGGER.debug("[DungeonTrain] EditorTypeMenu group phase {} {}: {}", PHASE_TOKENS[slot], action, cmd);
            CommandRunner.run(cmd);
            return;
        }
        String cmd = EditorPlotTeleport.phaseCommandFor(
            variant.category(), variant.modelId(), variant.modelName(),
            PHASE_TOKENS[slot], action);
        if (cmd == null) return;
        LOGGER.debug("[DungeonTrain] EditorTypeMenu phase {} {}: {}", PHASE_TOKENS[slot], action, cmd);
        CommandRunner.run(cmd);
    }

    private static boolean isSubVariants(EditorTypeMenusPacket.Menu menu) {
        return games.brennan.dungeontrain.editor.VariantOverlayRenderer.SUB_VARIANTS_TYPE_NAME
            .equals(menu.typeName());
    }

    /**
     * Open the keyboard worldspace menu directly at the matching "new
     * variant" picker for this menu's category. Uses
     * {@link CommandMenuState#openAt} so the picker is the only screen on
     * the navigation stack — clicking Back inside the picker closes the
     * menu rather than falling through to MainMenu / EditorMenu, keeping
     * the flow self-contained from the floating panel's perspective.
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

        // Sub-variants companion has a special typeName marker; route + New to
        // the CONTENTS_SUB_VARIANT picker (single-row name TypeArg) with the
        // parent id (= first row's modelId, the "default" sub-variant) as
        // currentId.
        if (games.brennan.dungeontrain.editor.VariantOverlayRenderer.SUB_VARIANTS_TYPE_NAME
                .equals(menu.typeName())) {
            LOGGER.debug("[DungeonTrain] EditorTypeMenu New: sub-variant of parent '{}'", first.modelId());
            CommandMenuState.openAt(new NewSourcePickerScreen(
                NewSourcePickerScreen.Category.CONTENTS_SUB_VARIANT, null, first.modelId()));
            return;
        }

        NewSourcePickerScreen picker = switch (category) {
            case "CARRIAGES" -> new NewSourcePickerScreen(
                NewSourcePickerScreen.Category.CARRIAGES, null, currentId);
            case "CONTENTS" -> new NewSourcePickerScreen(
                NewSourcePickerScreen.Category.CONTENTS, null, currentId);
            // For PARTS the modelId is the kind tag (floor / walls / roof /
            // doors); the picker's "Current" option needs a part variant id,
            // which the variant rows don't represent for the floating-menu
            // entry, so leave currentId blank.
            case "PARTS" -> new NewSourcePickerScreen(
                NewSourcePickerScreen.Category.PARTS, first.modelId(), "");
            // Tracks have no source choice today — picker collapses to a
            // single name TypeArg row. Kind tag is the variant's modelId
            // (the server's track-new parser expects the prefixed forms,
            // see EditorPlotTeleport).
            case "TRACKS" -> new NewSourcePickerScreen(
                NewSourcePickerScreen.Category.TRACKS, first.modelId(), "");
            default -> null;
        };
        if (picker == null) {
            LOGGER.warn("[DungeonTrain] EditorTypeMenu New: unsupported category '{}'", category);
            return;
        }
        CommandMenuState.openAt(picker);
    }
}
