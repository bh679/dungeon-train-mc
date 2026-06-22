package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientVoidBand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales the {@code MUSIC} sound-source volume by the disintegration band's position-driven
 * {@link ClientVoidBand#musicVolumeFactor} so the soundtrack crossfades as the player crosses the
 * void/End band (Overworld track fades out, End track fades in, and back on the way out).
 *
 * <p>1.21.1's {@code MusicManager} has no gain control — music is a fixed-volume, non-tickable
 * {@code SimpleSoundInstance} whose gain is {@code instanceVolume × getVolume(MUSIC)}, and
 * {@code getVolume} reads the user's options volume directly. Scaling that return value is the
 * one place to fade the currently playing track by position. {@code VoidSkyEvents.onClientTick}
 * re-applies the {@code MUSIC} volume each tick so the playing channel picks up the new gain.</p>
 *
 * <p>The factor is {@code 1.0} (a no-op) everywhere except inside an active band, so this only
 * affects Overworld music during a disintegration crossing.</p>
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMusicFadeMixin {

    @Inject(method = "getVolume(Lnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$fadeMusicAcrossVoidBand(SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (source != SoundSource.MUSIC) return;
        float original = cir.getReturnValueF();
        if (original <= 0.0F) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double factor = ClientVoidBand.musicVolumeFactor(mc.player.getX());
        if (factor < 1.0) {
            cir.setReturnValue((float) (original * factor));
        }
    }
}
