package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The press-arm / release-fire plumbing every world-space panel's input handler shares.
 *
 * <p>A click on a billboarded panel is a press the game would otherwise read as attack / use. The
 * press arms (and is cancelled, swing and all, with the crosshair hit cleared so no block damage
 * accumulates under it); the release of the <i>same</i> button, with the panel still handling input
 * and no screen up, fires the hit under the crosshair <i>at release</i>. Losing the right to handle
 * input in between disarms. Shift is read at press time while no screen is open and again from the
 * release's modifiers, so either half of a shift-click counts.</p>
 *
 * <p>{@code H} is the handler's own hover record. The handler keeps its static event subscribers
 * (NeoForge needs them static), each a one-line delegation to its instance of this class, and keeps
 * its own {@code shouldHandle} and {@code dispatch}.</p>
 *
 * @param <H> the panel's hover record
 */
public final class WorldPanelInput<H> {

    /** What a fired click does: the hit under the crosshair, whether shift was down, and the button. */
    @FunctionalInterface
    public interface Fire<H> {
        void fire(H hit, boolean shift, int button);
    }

    /**
     * The armed state between a press and its release. Pure, so the rule can be pinned in a test.
     *
     * @param armed  a press on a panel cell has not yet been released
     * @param button the GLFW button that armed it — only its own release fires
     * @param shift  shift was down at press time
     */
    public record Arm(boolean armed, int button, boolean shift) {
        public static final Arm IDLE = new Arm(false, -1, false);

        public static Arm pressed(int button, boolean shift) {
            return new Arm(true, button, shift);
        }

        /**
         * True when a mouse event is the release this arm fires on: a release of the button that
         * armed it, among the buttons the panel takes ({@code LEFT}, plus {@code RIGHT} when
         * {@code rightClickArms}).
         */
        public boolean firesOn(int button, int action, boolean rightClickArms) {
            if (!armed) return false;
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
            if (!rightClickArms && button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            if (action != GLFW.GLFW_RELEASE) return false;
            return button == this.button;
        }
    }

    private final Supplier<H> hovered;
    private final Predicate<H> isNone;
    private final BooleanSupplier shouldHandle;
    private final boolean rightClickArms;
    private final Fire<H> fire;
    private Arm arm = Arm.IDLE;

    /**
     * @param hovered        the panel's hit under the crosshair
     * @param isNone         true for the hover record that means "not on the panel"
     * @param shouldHandle   whether this panel owns the pointer right now
     * @param rightClickArms whether a use-item press (right button) arms as well as an attack
     * @param fire           what a fired click does
     */
    public WorldPanelInput(Supplier<H> hovered, Predicate<H> isNone, BooleanSupplier shouldHandle,
                           boolean rightClickArms, Fire<H> fire) {
        this.hovered = hovered;
        this.isNone = isNone;
        this.shouldHandle = shouldHandle;
        this.rightClickArms = rightClickArms;
        this.fire = fire;
    }

    /** A left-only panel: fires {@code onClick} on a left release, swallows a right press on a cell. */
    public static <H> WorldPanelInput<H> leftOnly(Supplier<H> hovered, Predicate<H> isNone,
                                                  BooleanSupplier shouldHandle, java.util.function.Consumer<H> onClick) {
        return new WorldPanelInput<>(hovered, isNone, shouldHandle, false, (hit, shift, button) -> onClick.accept(hit));
    }

    /** The armed state, for tests and for a handler that needs to know a press is pending. */
    public Arm arm() {
        return arm;
    }

    /** The press: cancel the world interaction and arm, when the crosshair is on a panel cell. */
    public void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle.getAsBoolean()) return;
        H hit = hovered.get();
        if (isNone.test(hit)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        Minecraft mc = Minecraft.getInstance();
        int button = rightClickArms && event.isUseItem() ? GLFW.GLFW_MOUSE_BUTTON_RIGHT : GLFW.GLFW_MOUSE_BUTTON_LEFT;
        arm = Arm.pressed(button, mc.screen == null && shiftDown(mc));
        // Clobber hitResult so the next tick's continueAttack sees a miss — prevents block-mining
        // progress from accumulating during the click.
        if (mc.player != null) {
            mc.hitResult = BlockHitResult.miss(mc.player.getEyePosition(), Direction.UP, mc.player.blockPosition());
        }
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    /** The release: fire the hit under the crosshair when it is the armed button's release. */
    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!shouldHandle.getAsBoolean()) {
            arm = Arm.IDLE;
            return;
        }
        if (Minecraft.getInstance().screen != null) return;
        int button = event.getButton();
        if (!arm.firesOn(button, event.getAction(), rightClickArms)) return;
        boolean shift = arm.shift() || (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        arm = Arm.IDLE;
        H hit = hovered.get();
        if (isNone.test(hit)) return;
        fire.fire(hit, shift, button);
    }

    /** Belt and braces: a left-click-block that slipped past the press cancel must not start block damage. */
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle.getAsBoolean()) return;
        if (isNone.test(hovered.get())) return;
        event.setCanceled(true);
    }

    private static boolean shiftDown(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }
}
