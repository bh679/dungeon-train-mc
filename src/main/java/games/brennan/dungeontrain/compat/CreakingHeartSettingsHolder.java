package games.brennan.dungeontrain.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * Implemented by VanillaBackport's creaking heart block entity through
 * {@code mixin/vanillabackport/CreakingHeartBlockEntityMixin}: its {@link CreakingHeartSettings}.
 */
public interface CreakingHeartSettingsHolder {

    CreakingHeartSettings dungeontrain$settings();

    void dungeontrain$setSettings(CreakingHeartSettings settings);

    /** The carriage interior this heart was stamped into, relative to it; null when it never was. */
    @org.jetbrains.annotations.Nullable
    net.minecraft.world.level.levelgen.structure.BoundingBox dungeontrain$bounds();

    void dungeontrain$setBounds(@org.jetbrains.annotations.Nullable net.minecraft.world.level.levelgen.structure.BoundingBox relative);

    /** The heart's settings at {@code pos}, or {@link CreakingHeartSettings#DEFAULT} if there is no heart. */
    static CreakingHeartSettings at(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CreakingHeartSettingsHolder holder
            ? holder.dungeontrain$settings() : CreakingHeartSettings.DEFAULT;
    }
}
