package games.brennan.dungeontrain.mixin.client.audio;

import games.brennan.dungeontrain.client.sound.DeathMenuHush;
import games.brennan.dungeontrain.client.sound.DeathMenuHushFade;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales the world's sound categories by {@link DeathMenuHush#factor()}, so the world is silent
 * behind the perks menu opened on a death. {@code getVolume} feeds every gain the engine computes
 * (new sounds, tickable sounds each tick, category re-applies), so one hook covers sounds already
 * playing and ones that start during the hush. A no-op whenever the factor is 1.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineDeathHushMixin {

    @Inject(method = "getVolume(Lnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$hushWorldBehindDeathMenu(SoundSource source, CallbackInfoReturnable<Float> cir) {
        float factor = DeathMenuHush.factor();
        if (factor >= 1.0F || !DeathMenuHushFade.hushes(source)) return;
        float original = cir.getReturnValueF();
        if (original <= 0.0F) return;
        cir.setReturnValue(DeathMenuHushFade.apply(original, factor));
    }
}
