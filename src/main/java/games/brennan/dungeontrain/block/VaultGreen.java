package games.brennan.dungeontrain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.joml.Vector3f;

/**
 * The green vault: a third vault type beside vanilla's trial and ominous ones, opened by the Green
 * Trial Key. It is the vanilla vault with one more block-state property, {@link #GREEN}, added by
 * {@code mixin.VaultBlockMixin}; its look comes from DT's override of vanilla's vault blockstate,
 * and {@code mixin.VaultClientParticlesMixin} swaps its flames for {@link #FLAME}.
 *
 * <p>Everything reads the property through {@link #isGreen} / {@link #withGreen}, which leave a state
 * without it alone: unit tests run vanilla blocks unmixed.</p>
 */
public final class VaultGreen {

    public static final BooleanProperty GREEN = BooleanProperty.create("green");

    /** The green vault's flame, in place of the trial vault's small flame and the ominous soul flame. */
    public static final ParticleOptions FLAME = new DustParticleOptions(new Vector3f(0.35f, 0.95f, 0.35f), 1.0f);

    private VaultGreen() {}

    public static boolean isGreen(BlockState state) {
        return state.hasProperty(GREEN) && state.getValue(GREEN);
    }

    public static boolean isGreenAt(BlockGetter level, BlockPos pos) {
        return isGreen(level.getBlockState(pos));
    }

    /** {@code state} with {@link #GREEN} set to {@code green}, or unchanged when it has no such property. */
    public static BlockState withGreen(BlockState state, boolean green) {
        return state.hasProperty(GREEN) ? state.setValue(GREEN, green) : state;
    }
}
