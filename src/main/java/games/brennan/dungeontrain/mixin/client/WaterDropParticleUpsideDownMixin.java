package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turn the rain drops around in the upside-down band, so they rise with the sheets instead of falling
 * against them.
 *
 * <p>{@link WaterDropParticle} backs {@code ParticleTypes.RAIN} and nothing else: its constructor pops
 * the drop upward and sets {@code gravity = 0.06}, and {@code tick()} then applies
 * {@code yd -= gravity} every tick. Negating both at the end of the constructor is the whole flip —
 * the drop pops down and accelerates up, and vanilla's own {@code move()} still stops it against the
 * ceiling it splashes on while {@code lifetime} still expires it.</p>
 *
 * <p>The mixin extends {@link Particle} rather than {@code @Shadow}-ing {@code x}/{@code yd}/{@code
 * gravity}: those fields are declared on {@code Particle}, not on the target, and Mixin only resolves a
 * shadow against the target class itself. Standing in the target's own hierarchy makes them ordinary
 * inherited protected fields.</p>
 *
 * <p>Gated per-drop on the drop's own X, so drops spawned outside the band (including in the exit
 * crossfade, where the overworld is upright again) keep falling normally. Placement is left on vanilla for now
 * (see {@code LevelRendererUpsideDownRainMixin}), so in the band this fires only for the few drops
 * vanilla still manages to spawn — but it costs nothing and keeps every drop that does appear pointing
 * the right way.</p>
 */
@Mixin(WaterDropParticle.class)
public abstract class WaterDropParticleUpsideDownMixin extends Particle {

    private WaterDropParticleUpsideDownMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z); // never called: the mixin is merged into the target, not instantiated
    }

    @Inject(method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDD)V", at = @At("TAIL"))
    private void dungeontrain$riseInUpsideDownBand(ClientLevel level, double x, double y, double z,
                                                   CallbackInfo ci) {
        if (!ClientUpsideDownBand.isInBand(Mth.floor(this.x))) return;
        this.yd = -this.yd;
        this.gravity = -this.gravity;
    }
}
