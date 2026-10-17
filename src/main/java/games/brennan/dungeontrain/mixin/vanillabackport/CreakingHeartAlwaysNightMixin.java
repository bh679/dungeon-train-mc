package games.brennan.dungeontrain.mixin.vanillabackport;

import com.blackgear.vanillabackport.common.level.blocks.CreakingHeartBlock;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps creaking hearts awake by day as well as by night.
 *
 * <p>VanillaBackport gates every heart behaviour (waking, spawning its Creaking,
 * the idle sound) on {@code doCreakingHeartsWorkOnDay || isNaturalNight(level)}.
 * That option defaults off and lives in a player-editable config, so DT answers
 * the other half instead: {@code isNaturalNight} is only read by the creaking
 * heart (Eyeblossoms and firefly bushes ask {@code LevelUtils} directly), so
 * forcing it changes nothing else. Runs on both sides; the idle sound is client-only.</p>
 */
@Mixin(value = CreakingHeartBlock.class, remap = false)
public abstract class CreakingHeartAlwaysNightMixin {

    @Inject(method = "isNaturalNight", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$workByDay(Level level, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
