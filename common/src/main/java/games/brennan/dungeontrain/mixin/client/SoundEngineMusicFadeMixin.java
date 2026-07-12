package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientNetherBand;
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
 * Scales the {@code MUSIC} sound-source volume by a special band's position-driven music volume
 * factor so the soundtrack crossfades as the player crosses it — the End band's
 * {@link ClientVoidBand#musicVolumeFactor} (Overworld→End and back) and the Nether transition
 * band's {@link ClientNetherBand#musicVolumeFactor} (Overworld→Nether and back).
 *
 * <p>1.21.1's {@code MusicManager} has no gain control — music is a fixed-volume, non-tickable
 * {@code SimpleSoundInstance} whose gain is {@code instanceVolume × getVolume(MUSIC)}, and
 * {@code getVolume} reads the user's options volume directly. Scaling that return value is the
 * one place to fade the currently playing track by position. The bands' {@code onClientTick}
 * handlers ({@code VoidSkyEvents} / {@code NetherFogEvents}) re-apply the {@code MUSIC} volume
 * each tick so the playing channel picks up the new gain.</p>
 *
 * <p>Both factors are {@code 1.0} (a no-op) outside their own band, and the two bands never
 * overlap in world-X, so taking the {@code min} cleanly selects whichever band the player is in
 * and stays a no-op everywhere else. This only affects Overworld music during a band crossing.</p>
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMusicFadeMixin {

    @Inject(method = "getVolume(Lnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$fadeMusicAcrossSpecialBand(SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (source != SoundSource.MUSIC) return;
        float original = cir.getReturnValueF();
        if (original <= 0.0F) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        double x = mc.player.getX();
        double factor = Math.min(ClientVoidBand.musicVolumeFactor(x), ClientNetherBand.musicVolumeFactor(x));
        if (factor < 1.0) {
            cir.setReturnValue((float) (original * factor));
        }
    }
}
