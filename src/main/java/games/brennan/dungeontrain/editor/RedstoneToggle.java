package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
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
 * <p>Property precedence (first match wins):
 * <ol>
 *   <li>{@link BlockStateProperties#OPEN} — trapdoors, doors, fence gates (doors
 *       also carry {@code powered}; the visible one wins).</li>
 *   <li>{@link BlockStateProperties#EXTENDED} — pistons.</li>
 *   <li>{@link BlockStateProperties#TRIGGERED} — dispensers, droppers.</li>
 *   <li>{@link BlockStateProperties#LIT} — only on {@link RedstoneLampBlock} and
 *       {@link RedstoneTorchBlock} (wall torches extend it). Candles, furnaces
 *       and campfires are lit by fire, not signal, so they stay untouched.</li>
 *   <li>{@link BlockStateProperties#ENABLED} — hoppers; active = enabled.</li>
 *   <li>{@link BlockStateProperties#POWERED} — levers, buttons, pressure plates,
 *       rails, note blocks, observers, tripwire, bells, lecterns.</li>
 * </ol>
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
     * The boolean property this block toggles on a redstone signal, or
     * {@code null} when the block has none (the UI hides the pill).
     */
    @Nullable
    public static BooleanProperty propertyFor(@Nullable BlockState state) {
        if (state == null) return null;
        if (state.hasProperty(BlockStateProperties.OPEN)) return BlockStateProperties.OPEN;
        if (state.hasProperty(BlockStateProperties.EXTENDED)) return BlockStateProperties.EXTENDED;
        if (state.hasProperty(BlockStateProperties.TRIGGERED)) return BlockStateProperties.TRIGGERED;
        if (state.hasProperty(BlockStateProperties.LIT) && isRedstoneLit(state.getBlock())) {
            return BlockStateProperties.LIT;
        }
        if (state.hasProperty(BlockStateProperties.ENABLED)) return BlockStateProperties.ENABLED;
        if (state.hasProperty(BlockStateProperties.POWERED)) return BlockStateProperties.POWERED;
        return null;
    }

    private static boolean isRedstoneLit(Block block) {
        return block instanceof RedstoneLampBlock || block instanceof RedstoneTorchBlock;
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
