package games.brennan.dungeontrain.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import games.brennan.dungeontrain.client.PlotLerpRecovery;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Never lets a client mob smooth toward a carriage's plot coordinates as if they were world ones.
 *
 * <p>For a mob riding a carriage the server sends its position in Sable plot space (x near
 * 20.48M), and Sable's client packet handler hands those coordinates to vanilla {@code lerpTo}.
 * That is harmless while Sable follows the mob: its own {@code aiStep} HEAD hook
 * ({@code LivingEntityMixin#sable$setupLerp}) takes over the steps and zeroes vanilla's
 * {@code lerpSteps}. But it only does so while the mob has a plot position, and a carriage
 * hold/reload removes the old client sub-level a moment before the new one (same uuid) arrives.
 * In that gap Sable clears the plot position, vanilla's lerp runs one step toward the plot target,
 * and the mob lands about 6.8M blocks out in an unloaded client chunk, frozen and invisible while
 * the real one keeps attacking (Lyraluithin, 0.1547.0).</p>
 *
 * <p>So: a pending vanilla lerp whose target is inside the plot container, on a mob Sable is not
 * following, is cancelled and handed to {@link PlotLerpRecovery}, which re-attaches the mob once
 * its carriage is back. Order against Sable's hook does not matter: a mob Sable follows has a plot
 * position or no steps left, and either one skips this. Recheck {@code sable$setupLerp} on a Sable
 * bump.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPlotLerpGuardMixin {

    @Shadow protected int lerpSteps;
    @Shadow protected double lerpX;
    @Shadow protected double lerpY;
    @Shadow protected double lerpZ;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void dungeontrain$holdPlotLerp(CallbackInfo ci) {
        if (lerpSteps <= 0) return;
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ClientLevel level)) return;
        if (((EntityStickExtension) self).sable$getPlotPosition() != null) return;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        Vec3 target = new Vec3(lerpX, lerpY, lerpZ);
        if (!container.inBounds(BlockPos.containing(target))) return;
        lerpSteps = 0;
        PlotLerpRecovery.hold(self, target);
    }
}
