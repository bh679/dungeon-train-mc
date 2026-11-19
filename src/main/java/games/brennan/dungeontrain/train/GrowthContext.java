package games.brennan.dungeontrain.train;

import java.util.function.BooleanSupplier;

/**
 * Marks the block changes a tree (or huge fungus / huge mushroom) makes as it grows on a carriage, so
 * {@code SableBlockChangeGuardMixin} can claim every cell it fills as a passenger's block
 * ({@link PlayerPlacedTrainBlocks}). Growth is not part of the train's build: a branch or canopy that
 * sticks out past the carriage breaks off when it hits terrain ({@link PlayerBlockBreakOff}), the way
 * bamboo does, instead of ploughing the world.
 *
 * <p>A tree is placed by a feature writing dozens of cells, so unlike bamboo there is no single new
 * position to mark; the growth call is wrapped instead ({@code TreeGrowthMixin},
 * {@code FungusGrowthMixin}, {@code MushroomGrowthMixin}). Server thread only; the previous value is
 * restored in a {@code finally}, so a nested growth (a feature that grows another) never ends the
 * outer one early.</p>
 */
public final class GrowthContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private GrowthContext() {}

    /** Whether the block change being handled right now was made by a growing tree. */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** Run {@code growAction} as a growth; returns what it returns. */
    public static boolean run(BooleanSupplier growAction) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return growAction.getAsBoolean();
        } finally {
            ACTIVE.set(previous);
        }
    }
}
