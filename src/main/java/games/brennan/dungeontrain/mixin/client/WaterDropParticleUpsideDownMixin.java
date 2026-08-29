package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.WaterDropParticle;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * <p>Gated per-drop on the drop's own X, so drops spawned outside the band (including in the exit
 * crossfade, where the overworld is upright again) keep falling normally. Placement of the splashes is
 * the other half of this: {@code UpsideDownRainRenderer.tickRain} puts them on the <em>underside</em> of
 * the terrain overhead.</p>
 */
@Mixin(WaterDropParticle.class)
public abstract class WaterDropParticleUpsideDownMixin {

    @Shadow protected double x;
    @Shadow protected double yd;
    @Shadow protected float gravity;

    @Inject(method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDD)V", at = @At("TAIL"))
    private void dungeontrain$riseInUpsideDownBand(ClientLevel level, double x, double y, double z,
                                                   CallbackInfo ci) {
        if (!ClientUpsideDownBand.isInBand(Mth.floor(this.x))) return;
        this.yd = -this.yd;
        this.gravity = -this.gravity;
    }
}
