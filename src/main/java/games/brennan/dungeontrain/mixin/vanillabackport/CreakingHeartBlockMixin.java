package games.brennan.dungeontrain.mixin.vanillabackport;

import com.blackgear.platform.core.util.config.ConfigBuilder;
import com.blackgear.vanillabackport.common.level.blocks.CreakingHeartBlock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.compat.CreakingHeartSettingsHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The block side of a heart's Day / Night settings: waking an uprooted heart once its logs are
 * back ({@code updateState}) and the idle sound ({@code animateTick}, client, read from the
 * client block entity). Same rule as {@link CreakingHeartSettingsMixin}: VanillaBackport's global
 * "work on day" config is answered {@code false} and the heart's own settings decide.
 */
@Mixin(value = CreakingHeartBlock.class, remap = false)
public abstract class CreakingHeartBlockMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final String NIGHT = "Lcom/blackgear/vanillabackport/common/level/blocks/CreakingHeartBlock;isNaturalNight(Lnet/minecraft/world/level/Level;)Z";
    @org.spongepowered.asm.mixin.Unique
    private static final String CONFIG = "Lcom/blackgear/platform/core/util/config/ConfigBuilder$ConfigValue;get()Ljava/lang/Object;";

    @WrapOperation(method = "updateState", at = @At(value = "INVOKE", target = NIGHT))
    private static boolean dungeontrain$awakeNow(Level level, Operation<Boolean> original,
                                                 @Local(argsOnly = true) BlockPos pos) {
        return CreakingHeartSettingsHolder.at(level, pos).awake(original.call(level));
    }

    @WrapOperation(method = "updateState", at = @At(value = "INVOKE", target = CONFIG))
    private static Object dungeontrain$noGlobalDayOverride(ConfigBuilder.ConfigValue<?> value, Operation<Object> original) {
        return Boolean.FALSE;
    }

    @WrapOperation(method = "animateTick", at = @At(value = "INVOKE", target = NIGHT))
    private boolean dungeontrain$awakeNowSound(Level level, Operation<Boolean> original,
                                               @Local(argsOnly = true) BlockPos pos) {
        return CreakingHeartSettingsHolder.at(level, pos).awake(original.call(level));
    }

    @WrapOperation(method = "animateTick", at = @At(value = "INVOKE", target = CONFIG))
    private Object dungeontrain$noGlobalDayOverrideSound(ConfigBuilder.ConfigValue<?> value, Operation<Object> original) {
        return Boolean.FALSE;
    }
}
