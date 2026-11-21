package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.sable.CarriageMobAi;
import net.minecraft.world.entity.monster.Evoker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts an evoker's fangs on the carriage deck it is fighting on.
 *
 * <p><b>Why:</b> {@code createSpellEntity} walks down the <i>world</i> column for a sturdy floor.
 * Under a moving carriage that column is the air above the track (the deck lives in the plot), so
 * no fang ever appeared on a train; only the vexes did any damage.</p>
 *
 * <p><b>How:</b> for an evoker Sable is carrying, the same search runs over the carriage's plot
 * blocks ({@link CarriageMobAi#spawnFangOnCarriage}) and the fang is placed on the deck. No
 * carriage, or no floor on it in the search window, and vanilla runs as before.</p>
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Evoker$EvokerAttackSpellGoal")
public abstract class EvokerFangCarriageFloorMixin {

    @Shadow
    @Final
    Evoker this$0;

    @Inject(method = "createSpellEntity(DDDDFI)V", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$fangOnCarriage(double x, double z, double minY, double maxY, float yRot,
                                            int warmupDelay, CallbackInfo ci) {
        if (CarriageMobAi.spawnFangOnCarriage(this.this$0, x, z, minY, maxY, yRot, warmupDelay)) {
            ci.cancel();
        }
    }
}
