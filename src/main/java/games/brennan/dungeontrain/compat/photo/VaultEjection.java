package games.brennan.dungeontrain.compat.photo;

/**
 * "A vault is ejecting a reward right now." Entered and left around {@code VaultState.ejectResultItem} by
 * {@code mixin.VaultEjectMixin}, so the {@code EntityJoinLevelEvent} that fires inside it lets a photo won
 * from a vault land intact for the player who unlocked it; it burns as normal once viewed or dropped again.
 * Server thread only, synchronous, nestable.
 */
public final class VaultEjection {

    private static int depth;

    private VaultEjection() {}

    public static void begin() {
        depth++;
    }

    public static void end() {
        if (depth > 0) depth--;
    }

    /** True while a vault's reward eject is on the stack. */
    public static boolean inProgress() {
        return depth > 0;
    }
}
