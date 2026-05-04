package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton client-side state for the worldspace command menu.
 *
 * <p>Navigation is a stack of {@link MenuScreen}s. When the player opens
 * the menu while standing in an editor plot (as reported by
 * {@link EditorStatusHudOverlay#isActive()}), the stack is initialised
 * with {@code [MainMenuScreen, EditorMenuScreen]} so a Back press from
 * the editor menu pops to the main menu. Outside an editor plot the
 * stack starts at {@code [MainMenuScreen]}.
 *
 * <p>Anchor axes are captured at open time and stay fixed for the life
 * of the menu instance, so the panel has a stable "up" even if the
 * player tilts their head. The renderer uses these axes; the hover
 * raycast uses the same.
 *
 * <p>Hover is tracked as {@code (rowIdx, subIdx)} — {@code subIdx} is 0
 * or 1 for {@link CommandMenuEntry.Split} rows (left vs. right button)
 * and always 0 for single-button rows.
 */
public final class CommandMenuState {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far in front of the player the panel anchors at open time. */
    private static final double ANCHOR_DISTANCE = 2.5;

    /** Auto-close if the player wanders this far from the anchor. */
    private static final double AUTO_CLOSE_DISTANCE_SQ = 20.0 * 20.0;

    private CommandMenuState() {}

    private static boolean open;
    private static Vec3 anchorPos = Vec3.ZERO;
    private static Vec3 anchorOffset = Vec3.ZERO;
    private static Vec3 anchorRight = new Vec3(1, 0, 0);
    private static Vec3 anchorUp = new Vec3(0, 1, 0);
    private static Vec3 anchorNormal = new Vec3(0, 0, 1);

    private static final List<MenuScreen> stack = new ArrayList<>();
    private static List<CommandMenuEntry> entries = List.of();

    private static int hoveredIdx = -1;
    private static int hoveredSubIdx = 0;

    private static boolean typingMode;
    private static String typedBuffer = "";
    private static String typingArgName = "";
    private static String typingCommandPrefix = "";
    private static String typingCommandSuffix = "";
    private static int typingOriginRowIdx = -1;
    private static int typingOriginSubIdx = 0;

    public static boolean isOpen() { return open; }
    public static Vec3 anchorPos() { return anchorPos; }
    public static Vec3 anchorOffset() { return anchorOffset; }
    public static Vec3 anchorRight() { return anchorRight; }
    public static Vec3 anchorUp() { return anchorUp; }
    public static Vec3 anchorNormal() { return anchorNormal; }

    /**
     * Recompute {@link #anchorPos} for the current frame using a partial-tick
     * interpolated eye position. Called from the renderer so the panel tracks
     * the player at frame rate, not tick rate — without this the panel jitters
     * by up to one tick (~50 ms) behind the camera while the player moves.
     */
    public static void refreshAnchorForFrame(float partialTick) {
        if (!open) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        anchorPos = p.getEyePosition(partialTick).add(anchorOffset);
    }
    public static List<CommandMenuEntry> entries() { return entries; }
    public static int hoveredIdx() { return hoveredIdx; }
    public static int hoveredSubIdx() { return hoveredSubIdx; }
    public static boolean typingMode() { return typingMode; }
    public static String typedBuffer() { return typedBuffer; }
    public static String typingArgName() { return typingArgName; }
    public static int typingOriginRowIdx() { return typingOriginRowIdx; }
    public static int typingOriginSubIdx() { return typingOriginSubIdx; }

    /** Full breadcrumb as a " > " separated title chain (for the header row). */
    public static String breadcrumb() {
        if (stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append(" > ");
            sb.append(stack.get(i).title());
        }
        return sb.toString();
    }

    public static void setHovered(int idx, int subIdx) {
        hoveredIdx = idx;
        hoveredSubIdx = subIdx;
    }

    /**
     * Open the menu in front of the local player. Chooses the starting
     * screen based on whether the player is in an editor plot.
     */
    public static void open() {
        if (EditorStatusHudOverlay.isActive()) {
            openInternal(List.of(new MainMenuScreen(), new EditorMenuScreen()));
        } else {
            openInternal(List.of(new MainMenuScreen()));
        }
    }

    /**
     * Open the menu with a single screen as the entire navigation stack —
     * Back from this screen closes the menu instead of falling through to
     * a parent. Used by sibling worldspace menus (e.g. the floating
     * template-type menu's "+ New" button) that want a self-contained
     * pop-up flow rather than threading through MainMenu / EditorMenu.
     *
     * <p>Same anchor, basis, and crosshair-clobber side effects as
     * {@link #open()}.</p>
     */
    public static void openAt(MenuScreen rootScreen) {
        openInternal(List.of(rootScreen));
    }

    private static void openInternal(List<MenuScreen> initialStack) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        anchorPos = eye.add(look.scale(ANCHOR_DISTANCE));
        // World-space offset captured at open time; the per-tick refresh keeps
        // the panel translating with the player while orientation stays fixed.
        anchorOffset = anchorPos.subtract(eye);

        anchorNormal = look.scale(-1.0).normalize();
        Vec3 worldUp = new Vec3(0, 1, 0);
        Vec3 up = worldUp.subtract(anchorNormal.scale(worldUp.dot(anchorNormal)));
        if (up.lengthSqr() < 1.0e-4) {
            up = new Vec3(0, 0, 1);
        }
        anchorUp = up.normalize();
        // Right-handed basis: X × Y = Z ⇒ right = up × normal.
        anchorRight = anchorUp.cross(anchorNormal).normalize();

        stack.clear();
        stack.addAll(initialStack);

        typingMode = false;
        typedBuffer = "";
        typingArgName = "";
        typingCommandPrefix = "";
        hoveredIdx = -1;
        hoveredSubIdx = 0;
        open = true;

        // Suppress any mining that was in progress when the menu opened,
        // and clobber the crosshair target so the tick after open can't
        // damage the block we popped up in front of. The per-frame renderer
        // keeps clobbering hitResult after this; stopDestroyBlock cancels
        // any lingering destroy state on the server.
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        mc.hitResult = net.minecraft.world.phys.BlockHitResult.miss(
            eye,
            net.minecraft.core.Direction.UP,
            player.blockPosition()
        );

        rebuildEntries();
        LOGGER.info("Command menu opened at {} (screens: {})", anchorPos, stack.size());
    }

    public static void close() {
        if (!open) return;
        open = false;
        typingMode = false;
        typedBuffer = "";
        hoveredIdx = -1;
        hoveredSubIdx = 0;
        typingOriginRowIdx = -1;
        typingOriginSubIdx = 0;
        anchorOffset = Vec3.ZERO;
        stack.clear();
        entries = List.of();
        dismissTypingScreen();
        LOGGER.debug("Command menu closed");
    }

    public static void drillIn(MenuScreen screen) {
        stack.add(screen);
        hoveredIdx = -1;
        hoveredSubIdx = 0;
        rebuildEntries();
    }

    public static void goBack() {
        if (typingMode) {
            cancelTyping();
            return;
        }
        if (stack.size() <= 1) {
            close();
            return;
        }
        stack.remove(stack.size() - 1);
        hoveredIdx = -1;
        hoveredSubIdx = 0;
        rebuildEntries();
    }

    public static void activate(int idx, int subIdx) {
        if (idx < 0 || idx >= entries.size()) return;
        CommandMenuEntry entry = entries.get(idx);
        LOGGER.info("Menu activate idx={} subIdx={} entry={}", idx, subIdx, entry);
        // Stash the click location BEFORE dispatchEntry, so a TypeArg activation
        // inside dispatch (possibly after a Split half-recurse) can pick up the
        // outer row/sub the user actually clicked. Renderer reads these to
        // place the inline typing field at the originating button.
        typingOriginRowIdx = idx;
        typingOriginSubIdx = subIdx;
        playClickSound();
        dispatchEntry(entry, subIdx);
    }

    /** Dispatch a single entry's action. Split rows recurse into the selected half. */
    private static void dispatchEntry(CommandMenuEntry entry, int subIdx) {
        if (entry instanceof CommandMenuEntry.Run run) {
            CommandRunner.run(run.command());
            close();
        } else if (entry instanceof CommandMenuEntry.Stay stay) {
            CommandRunner.run(stay.command());
            // Stay open so the player can click again. The next tick's
            // rebuild picks up any label change driven by server state.
        } else if (entry instanceof CommandMenuEntry.SaveAction save) {
            // Already-saved rows are no-ops — the cell is rendered greyed
            // and the raycast filters them out, but defensive double-check.
            if (save.saved()) return;
            save.onClick().run();
            // Stay open so the user can save other rows or click Continue.
            // The screen's onClick closure mutates its own local saved set
            // so the next tick's rebuild greys out the row.
        } else if (entry instanceof CommandMenuEntry.Label) {
            // Non-clickable. Reaching here means the raycast let a click
            // through somehow — silently ignore.
        } else if (entry instanceof CommandMenuEntry.ClientAction ca) {
            ca.action().run();
            // Same UX as Stay — keep the menu open so the player sees the
            // value tick up, but skip the slash-command round-trip for
            // pure client-side state.
        } else if (entry instanceof CommandMenuEntry.DrillIn drill) {
            drillIn(drill.target());
        } else if (entry instanceof CommandMenuEntry.Back) {
            goBack();
        } else if (entry instanceof CommandMenuEntry.TypeArg type) {
            beginTyping(type.argName(), type.commandPrefix(), type.commandSuffix(), type.initialBuffer());
        } else if (entry instanceof CommandMenuEntry.Toggle toggle) {
            String cmd = toggle.state() ? toggle.cmdToTurnOff() : toggle.cmdToTurnOn();
            CommandRunner.run(cmd);
            // Stay open so the user can see the state flip. The next tick's
            // rebuild will pick up the server-acked devmode value.
        } else if (entry instanceof CommandMenuEntry.Split split) {
            CommandMenuEntry target = subIdx == 1 ? split.rightEntry() : split.leftEntry();
            dispatchEntry(target, 0);
        } else if (entry instanceof CommandMenuEntry.Triple triple) {
            CommandMenuEntry target = switch (subIdx) {
                case 1 -> triple.middleEntry();
                case 2 -> triple.rightEntry();
                default -> triple.leftEntry();
            };
            dispatchEntry(target, 0);
        }
        // Loading — no-op.
    }

    /** Typing-mode activator that also captures a command suffix (e.g. the
     *  {@code [source]} after the typed name in {@code editor new <name> <source>}).
     *
     *  <p>Opens {@link MenuTypingScreen} so vanilla keybindings (movement,
     *  hotbar, inventory) pause while the player types. The screen is
     *  invisible — the worldspace menu's renderer keeps drawing the typing
     *  field underneath.</p>
     */
    public static void beginTyping(String argName, String prefix, String suffix) {
        beginTyping(argName, prefix, suffix, "");
    }

    /** As {@link #beginTyping(String, String, String)} but pre-populates the typing
     *  buffer with {@code initialBuffer} so the user can edit an existing value
     *  (used by Rename, where the current name is pre-filled). */
    public static void beginTyping(String argName, String prefix, String suffix, String initialBuffer) {
        typingMode = true;
        typedBuffer = initialBuffer == null ? "" : initialBuffer;
        if (typedBuffer.length() > 32) typedBuffer = typedBuffer.substring(0, 32);
        typingArgName = argName;
        typingCommandPrefix = prefix;
        typingCommandSuffix = suffix == null ? "" : suffix;
        hoveredIdx = -1;
        hoveredSubIdx = 0;
        Minecraft.getInstance().setScreen(new MenuTypingScreen());
    }

    public static void cancelTyping() {
        typingMode = false;
        typedBuffer = "";
        typingArgName = "";
        typingCommandPrefix = "";
        typingCommandSuffix = "";
        typingOriginRowIdx = -1;
        typingOriginSubIdx = 0;
        dismissTypingScreen();
    }

    public static void submitTyped() {
        if (!typingMode) return;
        if (typedBuffer.isEmpty()) return;
        String cmd = typingCommandPrefix + " " + typedBuffer;
        if (!typingCommandSuffix.isEmpty()) {
            cmd = cmd + " " + typingCommandSuffix;
        }
        CommandRunner.run(cmd);
        dismissTypingScreen();
        close();
    }

    /**
     * Pop our {@link MenuTypingScreen} if it's the active screen. Guarded so
     * we don't clobber a screen another mod (or the chat HUD) opened. The
     * screen's own {@link MenuTypingScreen#removed()} also calls
     * {@link #cancelTyping}; the {@code typingMode} guard there prevents
     * recursion when the cancel originates from this side.
     */
    private static void dismissTypingScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MenuTypingScreen) {
            mc.setScreen(null);
        }
    }

    public static void appendTyped(char c) {
        if (!typingMode) return;
        if (typedBuffer.length() >= 32) return;
        typedBuffer = typedBuffer + c;
    }

    public static void backspaceTyped() {
        if (!typingMode || typedBuffer.isEmpty()) return;
        typedBuffer = typedBuffer.substring(0, typedBuffer.length() - 1);
    }

    /**
     * Called each tick while open. Auto-closes if the player walks out
     * of range, and refreshes the entry list so live state (DevMode
     * toggle, editor status) flows through without the user needing to
     * drill away and back.
     */
    public static void onClientTick() {
        if (!open) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) { close(); return; }
        Vec3 eye = p.getEyePosition();
        anchorPos = eye.add(anchorOffset);
        if (eye.distanceToSqr(anchorPos) > AUTO_CLOSE_DISTANCE_SQ) {
            LOGGER.info("Command menu auto-closed (player wandered out of range)");
            close();
            return;
        }
        rebuildEntries();
    }

    public static void rebuildEntries() {
        if (!open || stack.isEmpty()) return;
        entries = stack.get(stack.size() - 1).entries();
    }

    private static void playClickSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }
}
