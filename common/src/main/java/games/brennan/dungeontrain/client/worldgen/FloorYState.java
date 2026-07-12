package games.brennan.dungeontrain.client.worldgen;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import java.util.Map;

/**
 * Client-side holder for the Floor-Y dropdown on the CreateWorldScreen.
 *
 * <p>The dropdown offers seven floor heights (multiples of 16). The selection persists
 * across Create-World sessions within a single game launch — opening and closing the
 * screen keeps the last choice sticky.
 *
 * <p>This class also carries two per-screen references (reset whenever a new
 * CreateWorldScreen/WorldTab pair is constructed):
 * <ul>
 *   <li>{@link #button} — the active {@link CycleButton} on the World tab. Lives inside
 *       the tab's GridLayout (appended by {@code CreateWorldScreenWorldTabMixin}) and is
 *       shown/hidden on preset change by {@code CreateWorldScreenMixin#tick}.</li>
 *   <li>{@link #presets} — the seven {@link Holder}s for the
 *       {@code dungeontrain:dungeon_train_y{N}} world presets, resolved once per screen
 *       init by {@code CreateWorldScreenMixin#init}. Used by both the WorldTab click
 *       handler and the screen-level tick logic.</li>
 * </ul>
 */
public final class FloorYState {

    public static final int[] VALUES = {-64, -48, -32, -16, 0, 16, 32, 48, 64, 80, 96};
    public static final int DEFAULT = 32;

    private static volatile int selectedY = DEFAULT;

    public static volatile CycleButton<Integer> button;
    public static volatile Map<Integer, Holder<WorldPreset>> presets;

    private FloorYState() {}

    public static int get() {
        return selectedY;
    }

    public static void set(int y) {
        selectedY = y;
    }
}
