package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.Nullable;

/**
 * Per-{@link VariantState} active/inactive config for blocks that latch a
 * toggled state without a standing signal — a trapdoor, door or fence gate's
 * {@code open}, a lever's {@code powered}, a copper bulb's {@code lit} (see
 * {@link RedstoneToggle#propertyFor}; signal-following blocks are excluded).
 * Independent of {@link VariantRotation} and {@link VariantHalf}.
 *
 * <p>Three modes:
 * <ul>
 *   <li>{@link Mode#ACTIVE} — force the toggle property {@code true} (open / powered / lit).</li>
 *   <li>{@link Mode#RANDOM} — roll active/inactive at spawn time via a seeded roll in
 *       {@link RedstoneToggle}, independent of the facing and half rolls.</li>
 *   <li>{@link Mode#INACTIVE} — force the toggle property {@code false}.</li>
 * </ul>
 *
 * <p>{@link Mode#INACTIVE} is the default: it matches what the Z+right-click
 * capture has always recorded (the item's placement state, which is never
 * open / lit / powered), so builds authored before this flag existed spawn
 * unchanged. The {@link VariantState} canonical constructor keeps the stored
 * state's toggle property in step with the mode, so the state string on
 * disk and the flag never disagree.</p>
 */
public record VariantActive(Mode mode) {

    public enum Mode { ACTIVE, RANDOM, INACTIVE }

    /** Default: inactive, matching the historical placement-state capture. */
    public static final VariantActive NONE = new VariantActive(Mode.INACTIVE);

    public VariantActive {
        if (mode == null) mode = Mode.INACTIVE;
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return mode == Mode.INACTIVE;
    }

    public static VariantActive active() {
        return new VariantActive(Mode.ACTIVE);
    }

    public static VariantActive random() {
        return new VariantActive(Mode.RANDOM);
    }

    /**
     * The mode a captured state implies when nothing explicit was authored:
     * {@link Mode#ACTIVE} if its redstone toggle is already {@code true} (a
     * world-block capture of an opened trapdoor, a hand-edited sidecar), else
     * the default. Used by every {@link VariantState} constructor that lacks an
     * explicit mode, and by the sidecar reader for pre-flag entries.
     */
    public static VariantActive fromState(@Nullable BlockState state) {
        BooleanProperty prop = RedstoneToggle.propertyFor(state);
        if (prop != null && state.getValue(prop)) return active();
        return NONE;
    }
}
