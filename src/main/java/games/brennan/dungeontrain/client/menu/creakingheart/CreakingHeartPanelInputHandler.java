package games.brennan.dungeontrain.client.menu.creakingheart;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.CommandRunner;
import games.brennan.dungeontrain.client.menu.EditorSaveStatus;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu;
import games.brennan.dungeontrain.client.menu.number.NumberField;
import games.brennan.dungeontrain.client.menu.number.NumberPad;
import games.brennan.dungeontrain.client.menu.number.NumberStepper;
import games.brennan.dungeontrain.client.menu.number.NumberTitle;
import games.brennan.dungeontrain.compat.CreakingHeartSettings;
import games.brennan.dungeontrain.compat.CreakingHeartSettingsHolder;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Mouse wiring for the creaking heart panel: the Z menu's press-arm / release-dispatch skeleton
 * ({@code BlockVariantMenuInputHandler}). Numbers use the shared stepper (click +, shift- or
 * right-click −, cmd types); every other cell runs a {@code creakingheart} command, so the panel
 * refreshes when the server sends the heart back.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CreakingHeartPanelInputHandler {

    private static boolean pressArmed;
    private static boolean pressShift;
    private static int pressButton;
    /** Ticks until the editor's unsaved list is asked for again after an edit; 0 when none is pending. */
    private static int saveStatusRefreshTicks;
    private static final int SAVE_STATUS_REFRESH_DELAY = 10;

    private CreakingHeartPanelInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        if (CreakingHeartPanel.hovered().kind() == CreakingHeartPanelLayout.Kind.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        Minecraft mc = Minecraft.getInstance();
        pressArmed = true;
        pressButton = event.isUseItem() ? GLFW.GLFW_MOUSE_BUTTON_RIGHT : GLFW.GLFW_MOUSE_BUTTON_LEFT;
        long window = mc.getWindow().getWindow();
        pressShift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (mc.player != null) {
            mc.hitResult = BlockHitResult.miss(mc.player.getEyePosition(), Direction.UP, mc.player.blockPosition());
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
        if (event.getAction() != GLFW.GLFW_RELEASE || !pressArmed || btn != pressButton) return;
        pressArmed = false;
        boolean shift = pressShift || (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        CreakingHeartPanelLayout.Hit hit = CreakingHeartPanel.hovered();
        if (hit.kind() != CreakingHeartPanelLayout.Kind.NONE) dispatch(hit, shift, btn);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (shouldHandle() && CreakingHeartPanel.hovered().kind() != CreakingHeartPanelLayout.Kind.NONE) {
            event.setCanceled(true);
        }
    }

    /** Closes the panel when the player walks away, the heart is gone, or the world unloads. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // An edit marks its plot unsaved on the server; ask once it has run so the Save icon catches up.
        if (saveStatusRefreshTicks > 0 && --saveStatusRefreshTicks == 0) EditorSaveStatus.request();
        if (!CreakingHeartPanel.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null
                || mc.player.getEyePosition().distanceTo(CreakingHeartPanel.anchor()) > CreakingHeartPanel.CLOSE_DISTANCE
                || !(mc.level.getBlockEntity(CreakingHeartPanel.heartPos()) instanceof CreakingHeartSettingsHolder)) {
            CreakingHeartPanel.close();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CreakingHeartPanel.close();
    }

    private static boolean shouldHandle() {
        if (!CreakingHeartPanel.isActive()) return false;
        if (CommandMenuState.isOpen()) return false;
        return !BlockVariantMenu.isActive();
    }

    private static void dispatch(CreakingHeartPanelLayout.Hit hit, boolean shift, int button) {
        boolean number = hit.kind() == CreakingHeartPanelLayout.Kind.COUNT_MIN
            || hit.kind() == CreakingHeartPanelLayout.Kind.COUNT_MAX
            || hit.kind() == CreakingHeartPanelLayout.Kind.MIN
            || hit.kind() == CreakingHeartPanelLayout.Kind.MAX;
        // Right-click steps a number down and does nothing else (the press is already swallowed).
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !number) return;
        if (hit.kind() == CreakingHeartPanelLayout.Kind.BACKDROP) {
            CreakingHeartPanel.setPopup(CreakingHeartPanel.Popup.NONE);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));

        String p = CreakingHeartPanel.commandPrefix();
        CreakingHeartSettings s = CreakingHeartPanel.settings();
        TemplateGate g = s.gate();
        switch (hit.kind()) {
            case CLOSE -> CreakingHeartPanel.close();
            case DAY -> run(p + " day " + (s.day() ? "off" : "on"));
            case NIGHT -> run(p + " night " + (s.night() ? "off" : "on"));
            case COUNT_MIN -> clickNumber(NumberField.ofCommand(MenuLang.t("creaking_heart.count_title"), p + " mincount",
                s.minCount(), CreakingHeartSettings.MIN_COUNT, CreakingHeartSettings.MAX_COUNT, false), button, shift);
            case COUNT_MAX -> clickNumber(NumberField.ofCommand(MenuLang.t("creaking_heart.count_title"), p + " maxcount",
                s.maxCount(), s.minCount(), CreakingHeartSettings.MAX_COUNT, false), button, shift);
            case MIN -> clickNumber(NumberField.ofCommand(NumberTitle.of("min_level"), p + " minlevel",
                g.minLevel(), 0, TemplateGate.MAX_LEVEL, false), button, shift);
            case MAX -> clickNumber(NumberField.ofCommand(NumberTitle.of("max_level"), p + " maxlevel",
                g.maxLevel() == TemplateGate.ALL ? NumberField.ALL : g.maxLevel(),
                g.minLevel(), TemplateGate.MAX_LEVEL, true), button, shift);
            case STAGE -> CreakingHeartPanel.setPopup(CreakingHeartPanel.popup() == CreakingHeartPanel.Popup.STAGE
                ? CreakingHeartPanel.Popup.NONE : CreakingHeartPanel.Popup.STAGE);
            case STAGE_OPTION -> {
                List<ClientStages.Info> stages = ClientStages.all();
                int i = hit.index();
                if (i == 0) {
                    run(p + " stage custom");
                } else if (i - 1 < stages.size()) {
                    run(p + " stage " + stages.get(i - 1).id());
                }
                CreakingHeartPanel.setPopup(CreakingHeartPanel.Popup.NONE);
            }
            case BAND -> {
                TrainPhase[] phases = TrainPhase.values();
                if (hit.index() < 0 || hit.index() >= phases.length) return;
                TrainPhase phase = phases[hit.index()];
                String op = shift ? "others" : (g.phases().contains(phase) ? "off" : "on");
                run(p + " phase " + phase.token() + " " + op);
            }
            default -> { }
        }
    }

    /** Runs one heart edit and schedules the Save-status refresh. */
    private static void run(String command) {
        CommandRunner.run(command);
        saveStatusRefreshTicks = SAVE_STATUS_REFRESH_DELAY;
    }

    /** The world-space panel stays up behind the number pad, so the pad returns to the world. */
    private static void clickNumber(NumberField field, int button, boolean shift) {
        NumberStepper.click(field, button, shift, MenuClickModifiers.cmdDown(), NumberPad.returningTo(null));
        saveStatusRefreshTicks = SAVE_STATUS_REFRESH_DELAY;
    }
}
