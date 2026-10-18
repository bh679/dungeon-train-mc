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

    /** The heart's settings at {@code pos}, or {@link CreakingHeartSettings#DEFAULT} if there is no heart. */
    static CreakingHeartSettings at(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CreakingHeartSettingsHolder holder
            ? holder.dungeontrain$settings() : CreakingHeartSettings.DEFAULT;
    }
}
