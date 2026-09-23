package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Pure-data applier for the {@link VariantActive} flag — the block-variant
 * sibling of {@link RotationApplier#applyHalf}. Decides which boolean
 * property on a {@link BlockState} is "the redstone toggle" and sets it per
 * the authored mode.
 *
 * <p>Only <b>latching</b> blocks qualify: ones whose toggled state is set by a
 * right-click or a redstone pulse and then <em>stays</em> once the signal is
 * gone, so a stamped carriage can hold it with no circuit behind it:
 * <ul>
 *   <li>{@link BlockStateProperties#OPEN} on trapdoors, doors and fence gates.</li>
 *   <li>{@link BlockStateProperties#POWERED} on levers.</li>
 *   <li>{@link BlockStateProperties#LIT} on copper bulbs (toggle on a rising edge, keep it).</li>
 * </ul>
 * Blocks that fall back the moment the signal stops — redstone lamps and
 * torches, pistons, dispensers, hoppers, buttons, pressure plates, rails,
 * note blocks — are deliberately <em>not</em> toggles: a stamped "on" state
 * would revert on the first neighbour update, so the pill would lie.</p>
 *
 * <p>Determinism contract for {@link VariantActive.Mode#RANDOM}: same
 * {@code (worldSeed, carriageIndex, localPos|lockId)} → same roll across
 * reloads, salted so it doesn't correlate with the facing or half rolls at
 * the same position.</p>
 */
public final class RedstoneToggle {

    /** Mix constant distinct from {@code ROT_SEED_SALT} / {@code FLIP_SEED_SALT}. */
    private static final long ACTIVE_SEED_SALT = 0x7C1B6A5D3E9F2481L;

    private RedstoneToggle() {}

    /**
     * The boolean property this block latches on a right-click / redstone
     * pulse, or {@code null} when the block has none (the UI hides the pill).
     */
    @Nullable
    public static BooleanProperty propertyFor(@Nullable BlockState state) {
        if (state == null) return null;
        Block block = state.getBlock();
        if (block instanceof TrapDoorBlock || block instanceof DoorBlock || block instanceof FenceGateBlock) {
            return state.hasProperty(BlockStateProperties.OPEN) ? BlockStateProperties.OPEN : null;
        }
        if (block instanceof LeverBlock) {
            return state.hasProperty(BlockStateProperties.POWERED) ? BlockStateProperties.POWERED : null;
        }
        if (block instanceof CopperBulbBlock) {
            return state.hasProperty(BlockStateProperties.LIT) ? BlockStateProperties.LIT : null;
        }
        return null;
    }

    /** True when {@link #propertyFor} finds a toggle — the menu's gate for the A/R/I pill. */
    public static boolean canToggle(@Nullable BlockState state) {
        return propertyFor(state) != null;
    }

    /**
     * Force the toggle property to {@code active}. Returns the state unchanged
     * when the block has no toggle.
     */
    public static BlockState set(BlockState state, boolean active) {
        if (state == null) return null;
        BooleanProperty prop = propertyFor(state);
        if (prop == null) return state;
        return state.setValue(prop, active);
    }

    /**
     * Apply the authored mode at spawn time. ACTIVE / INACTIVE force the
     * property; RANDOM rolls deterministically from the same inputs the
     * facing and half rolls use.
     */
    public static BlockState apply(BlockState state, @Nullable VariantActive active,
                                   BlockPos localPos, long worldSeed,
                                   int carriageIndex, int lockId) {
        if (state == null) return null;
        if (active == null) active = VariantActive.NONE;
        BooleanProperty prop = propertyFor(state);
        if (prop == null) return state;
        boolean on = switch (active.mode()) {
            case ACTIVE -> true;
            case RANDOM -> roll(localPos, worldSeed, carriageIndex, lockId);
            case INACTIVE -> false;
        };
        return state.setValue(prop, on);
    }

    /** Seeded coin flip — same recipe as {@link RotationApplier#applyHalf}, own salt. */
    static boolean roll(BlockPos localPos, long worldSeed, int carriageIndex, int lockId) {
        long posOrLock = lockId > 0
            ? (long) lockId * 0xBF58476D1CE4E5B9L
            : (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ()) * 0xBF58476D1CE4E5B9L;
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ posOrLock
            ^ ACTIVE_SEED_SALT;
        return new Random(seed).nextBoolean();
    }
}
