package games.brennan.dungeontrain.train;

/**
 * Marks the block changes a digging mob makes ({@code combat.MobDigGoal}), so the shared-carriage
 * hook in {@code SableBlockChangeGuardMixin} can tell them from a player's edit. Server thread only;
 * set around one break and cleared in a {@code finally}.
 */
public final class MobBreakContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private MobBreakContext() {}

    /** Whether the block change being handled right now is a mob's dig. */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** Run {@code breakAction} as a mob's dig; returns what it returns. */
    public static boolean run(java.util.function.BooleanSupplier breakAction) {
        ACTIVE.set(Boolean.TRUE);
        try {
            return breakAction.getAsBoolean();
        } finally {
            ACTIVE.set(Boolean.FALSE);
        }
    }
}
