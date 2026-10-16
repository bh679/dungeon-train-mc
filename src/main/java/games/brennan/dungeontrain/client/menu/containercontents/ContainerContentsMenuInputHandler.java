package games.brennan.dungeontrain.client.menu.containercontents;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.CommandMenuState;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.number.NumberField;
import games.brennan.dungeontrain.client.menu.number.NumberPad;
import games.brennan.dungeontrain.client.menu.number.NumberStepper;
import games.brennan.dungeontrain.client.menu.number.NumberTitle;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.net.ContainerContentsSyncPacket;
import games.brennan.dungeontrain.net.ContainerContentsEditPacket;
import games.brennan.dungeontrain.net.ContainerContentsMenuTogglePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Mouse + keyboard wiring for {@link ContainerContentsMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenuInputHandler}
 * with simpler dispatch (no rotation, no lock).
 *
 * <ul>
 *   <li>Click on count or weight cell — server bumps by +1 (shift = -1).</li>
 *   <li>Click on the entry icon — toggles that row's name reveal (client-only).</li>
 *   <li>Click on × cell — remove entry.</li>
 *   <li>Click ADD — open search screen.</li>
 *   <li>Click CLEAR — wipe pool.</li>
 *   <li>Click X (close) — close menu.</li>
 * </ul>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class ContainerContentsMenuInputHandler {

    private static boolean pressArmed;
    private static boolean pressShift;
    /** Which mouse button armed the press — only its own release dispatches. */
    private static int pressButton;

    /** The chance cells step in fives. */
    private static final int CHANCE_STEP = 5;

    /**
     * Ceiling for a typed weight. The stepper has no cap of its own — this is a sanity bound on
     * the number pad so a mistyped digit run can't drown every other entry in the pool.
     */
    private static final int TYPED_WEIGHT_MAX = 1000;

    private ContainerContentsMenuInputHandler() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!shouldHandle()) return;
        ContainerContentsMenu.Hit hit = ContainerContentsMenu.hovered();
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return;
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

        ContainerContentsMenu.Hit hit = ContainerContentsMenu.hovered();
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return;
        dispatch(hit, shift, btn);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!shouldHandle()) return;
        ContainerContentsMenu.Hit hit = ContainerContentsMenu.hovered();
        if (hit.kind() == ContainerContentsMenu.CellKind.NONE) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ContainerContentsSearchScreen
            && (!ContainerContentsMenu.isActive()
                || ContainerContentsMenu.screen() != ContainerContentsMenu.Screen.ADD_SEARCH)) {
            mc.setScreen(null);
        }
        if (!ContainerContentsMenu.isActiveWorldspace()) return;
        if (mc.screen == null) {
            ContainerContentsMenuRaycast.updateHovered();
        }
    }

    private static boolean shouldHandle() {
        if (!ContainerContentsMenu.isActiveWorldspace()) return false;
        if (CommandMenuState.isOpen()) return false;
        return true;
    }

    /**
     * Act on a hit cell. Package-visible because {@link ContainerContentsMenuScreen} dispatches
     * through this same body — the screen-space path differs only in where the Hit came from.
     */
    static void dispatch(ContainerContentsMenu.Hit hit, boolean shift, int button) {
        // Right-click is a number cell's step-down and nothing else.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !isNumberCell(hit)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        if (ContainerContentsMenu.screen() == ContainerContentsMenu.Screen.ROOT) {
            dispatchRoot(hit, shift, button);
        } else {
            dispatchSearch(hit);
        }
    }

    /** Whether {@code hit} is one of this panel's numbers. */
    private static boolean isNumberCell(ContainerContentsMenu.Hit hit) {
        if (ContainerContentsMenu.screen() != ContainerContentsMenu.Screen.ROOT) return false;
        return switch (hit.kind()) {
            case FILL_MIN, FILL_MAX, ENTRY_COUNT_PLUS, ENTRY_WEIGHT_PLUS, ENTRY_DUR_CHANCE, ENTRY_ENCH_CHANCE -> true;
            default -> false;
        };
    }

    private static void dispatchRoot(ContainerContentsMenu.Hit hit, boolean shift, int button) {
        net.minecraft.core.BlockPos local = ContainerContentsMenu.localPos();
        if (local == null) return;
        String plotKey = ContainerContentsMenu.plotKey();
        switch (hit.kind()) {
            case ADD -> DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                // Empty itemId signals "use main-hand item" — server captures
                // the held stack's item + count. Mirrors the block-variant
                // ADD behaviour: hold an item, click Add.
                ContainerContentsEditPacket.Op.ADD, plotKey, local, -1, "", 0));
            case SAVE -> {
                // Linked containers route every menu edit straight to the
                // template, so SAVE has no work to do — show a hint instead
                // of re-prompting for a name. First-time save (no link yet)
                // opens the name screen.
                String linked = ContainerContentsMenu.linkedPrefabId();
                if (linked != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                MenuLang.PREFIX + "container.linked_autosave", linked)
                                .withStyle(net.minecraft.ChatFormatting.AQUA),
                            true);
                    }
                } else {
                    // Screen-space: come back to the panel we are replacing, not the world.
                    Minecraft.getInstance().setScreen(
                        new games.brennan.dungeontrain.client.menu.PrefabNameScreen(
                            games.brennan.dungeontrain.client.menu.PrefabNameScreen.Kind.LOOT,
                            local,
                            ContainerContentsMenu.space().isScreenspace()
                                ? new ContainerContentsMenuScreen() : null));
                }
            }
            case FILL_MIN, FILL_MAX -> NumberStepper.click(fillNumber(hit.kind(), plotKey, local),
                button, shift, MenuClickModifiers.cmdDown(), pad());
            case CLEAR -> DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                ContainerContentsEditPacket.Op.CLEAR, plotKey, local, -1, "", 0));
            case CLOSE -> DungeonTrainNet.sendToServer(new ContainerContentsMenuTogglePacket(false));
            case ENTRY_COUNT_PLUS, ENTRY_WEIGHT_PLUS, ENTRY_DUR_CHANCE, ENTRY_ENCH_CHANCE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                NumberStepper.click(entryNumber(hit.kind(), plotKey, local, hit.index(),
                        ContainerContentsMenu.entries().get(hit.index())),
                    button, shift, MenuClickModifiers.cmdDown(), pad());
            }
            case ENTRY_ICON -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                // Purely cosmetic and client-side — no packet, the next render
                // picks the new width up from the shared layout.
                ContainerContentsMenu.toggleExpanded(hit.index());
            }
            case ENTRY_REMOVE_X -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.REMOVE, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_RAND_DUR_TOGGLE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.TOGGLE_RAND_DUR, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_RAND_ENCH_TOGGLE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.TOGGLE_RAND_ENCH, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_SCALE_TOGGLE -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.TOGGLE_SCALE, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_POTION_FORM -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.CYCLE_POTION_FORM, plotKey, local, hit.index(), "", 0));
            }
            case ENTRY_SLOT_ASSIGN -> {
                if (hit.index() < 0 || hit.index() >= ContainerContentsMenu.entries().size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.CYCLE_SLOT_ASSIGN, plotKey, local, hit.index(), "", 0));
            }
            case LINK_UNLINK -> DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                ContainerContentsEditPacket.Op.UNLINK, plotKey, local, -1, "", 0));
            // LINK_INDICATOR is informational — no click action.
            default -> {}
        }
    }

    /** The pool's fill min / max: steps with the BUMP op, types with its SET twin. */
    private static NumberField fillNumber(ContainerContentsMenu.CellKind kind, String plotKey,
                                          net.minecraft.core.BlockPos local) {
        int max = ContainerContentsMenu.fillMax();
        if (kind == ContainerContentsMenu.CellKind.FILL_MIN) {
            return new NumberField(NumberTitle.of("fill_min"), ContainerContentsMenu.fillMin(), 0,
                max < 0 ? ContainerContentsPool.MAX_FILL_BOUND : max, false,
                () -> send(ContainerContentsEditPacket.Op.BUMP_FILL_MIN, plotKey, local, -1, 1),
                () -> send(ContainerContentsEditPacket.Op.BUMP_FILL_MIN, plotKey, local, -1, -1),
                v -> send(ContainerContentsEditPacket.Op.SET_FILL_MIN, plotKey, local, -1, v));
        }
        return new NumberField(NumberTitle.of("fill_max"), max < 0 ? NumberField.ALL : max, 0,
            ContainerContentsPool.MAX_FILL_BOUND, true,
            () -> send(ContainerContentsEditPacket.Op.BUMP_FILL_MAX, plotKey, local, -1, 1),
            () -> send(ContainerContentsEditPacket.Op.BUMP_FILL_MAX, plotKey, local, -1, -1),
            v -> send(ContainerContentsEditPacket.Op.SET_FILL_MAX, plotKey, local, -1, v));
    }

    /** One entry's count, weight or chance cell. */
    private static NumberField entryNumber(ContainerContentsMenu.CellKind kind, String plotKey,
                                           net.minecraft.core.BlockPos local, int row,
                                           ContainerContentsSyncPacket.Entry e) {
        record Spec(String title, int value, int min, int max, int step,
                    ContainerContentsEditPacket.Op bump, ContainerContentsEditPacket.Op set) {}
        Spec spec = switch (kind) {
            case ENTRY_COUNT_PLUS -> new Spec("count", e.count(), 1, 64, 1,
                ContainerContentsEditPacket.Op.BUMP_COUNT, ContainerContentsEditPacket.Op.SET_COUNT);
            case ENTRY_DUR_CHANCE -> new Spec("durability_chance", e.durabilityChance(), 0, 100, CHANCE_STEP,
                ContainerContentsEditPacket.Op.BUMP_DUR_CHANCE, ContainerContentsEditPacket.Op.SET_DUR_CHANCE);
            case ENTRY_ENCH_CHANCE -> new Spec("enchant_chance", e.enchantmentChance(), 0, 100, CHANCE_STEP,
                ContainerContentsEditPacket.Op.BUMP_ENCH_CHANCE, ContainerContentsEditPacket.Op.SET_ENCH_CHANCE);
            default -> new Spec("weight", e.weight(), 1, TYPED_WEIGHT_MAX, 1,
                ContainerContentsEditPacket.Op.BUMP_WEIGHT, ContainerContentsEditPacket.Op.SET_WEIGHT);
        };
        return new NumberField(NumberTitle.of(spec.title()), spec.value(), spec.min(), spec.max(), false,
            () -> send(spec.bump(), plotKey, local, row, spec.step()),
            () -> send(spec.bump(), plotKey, local, row, -spec.step()),
            v -> send(spec.set(), plotKey, local, row, v));
    }

    private static void send(ContainerContentsEditPacket.Op op, String plotKey, net.minecraft.core.BlockPos local,
                             int row, int value) {
        DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(op, plotKey, local, row, "", value));
    }

    /**
     * Cmd-click types into the number pad, returning to the panel screen when the menu is
     * screen-space and to the world when it is world-space — same rule as the Save cell.
     */
    private static NumberStepper.Typing pad() {
        return NumberPad.returningToBuilt(
            () -> ContainerContentsMenu.space().isScreenspace() ? new ContainerContentsMenuScreen() : null);
    }

    private static void dispatchSearch(ContainerContentsMenu.Hit hit) {
        net.minecraft.core.BlockPos local = ContainerContentsMenu.localPos();
        if (local == null) return;
        String plotKey = ContainerContentsMenu.plotKey();
        switch (hit.kind()) {
            case SEARCH_BACK -> ContainerContentsMenu.backToRoot();
            case SEARCH_FIELD -> {
                // World-space has no screen of its own, so it needs an invisible one to stop
                // typed letters from walking the player. Screen-space is already a screen and
                // types inline — opening this over it would replace the panel being typed into.
                if (ContainerContentsMenu.space().isWorldspace()) {
                    Minecraft.getInstance().setScreen(new ContainerContentsSearchScreen());
                }
            }
            case SEARCH_RESULT -> {
                List<String> filtered = ContainerContentsMenu.filteredItemIds();
                if (hit.index() < 0 || hit.index() >= filtered.size()) return;
                DungeonTrainNet.sendToServer(new ContainerContentsEditPacket(
                    ContainerContentsEditPacket.Op.ADD, plotKey, local, -1, filtered.get(hit.index()), 0));
                ContainerContentsMenu.backToRoot();
            }
            default -> {}
        }
    }
}
