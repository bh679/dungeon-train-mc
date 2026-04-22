package games.brennan.dungeontrain.client.worldgen;

/**
 * Client-side holder for the Floor-Y dropdown selection on the CreateWorldScreen.
 *
 * <p>The dropdown offers seven floor heights (multiples of 16). When the user clicks
 * "Create New World", {@code CreateWorldScreenMixin} reads {@link #selectedY} and, if it
 * differs from {@link #DEFAULT}, swaps the active world preset from
 * {@code dungeontrain:dungeon_train} to {@code dungeontrain:dungeon_train_y{N}} before the
 * WorldCreationContext is built.
 *
 * <p>Value persists across Create-World sessions within a single game launch — opening and
 * closing the screen keeps the last choice sticky, which matches the behaviour of the
 * adjacent vanilla widgets (seed, preset).
 */
public final class FloorYState {

    public static final int[] VALUES = {0, 16, 32, 48, 64, 80, 96};
    public static final int DEFAULT = 32;

    private static volatile int selectedY = DEFAULT;

    private FloorYState() {}

    public static int get() {
        return selectedY;
    }

    public static void set(int y) {
        selectedY = y;
    }
}
