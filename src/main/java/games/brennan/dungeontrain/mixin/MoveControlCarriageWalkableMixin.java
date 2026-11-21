package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import games.brennan.dungeontrain.ship.sable.CarriageMobAi;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a strafing mob on a moving carriage strafe on the carriage's deck.
 *
 * <p><b>Why:</b> {@code MoveControl.isWalkable} asks for the path type of the <i>world</i> block
 * beside the mob. On a carriage that is the open air above the track (the deck lives in the plot),
 * so it is never walkable, and the STRAFE branch swaps the strafe for "walk forward". A skeleton
 * then charges the player instead of backing off, stands 0.5 blocks away, and every arrow after its
 * first goes point-blank into the deck. Same for anything using {@code strafe()}: bow skeletons
 * ({@code RangedBowAttackGoal}) and crossbow piglins ({@code BackUpIfTooClose}).</p>
 *
 * <p><b>How:</b> a vanilla {@code true} is kept. A {@code false} for a mob that Sable is carrying is
 * asked again of the carriage's plot blocks ({@link CarriageMobAi#walkableOnCarriage}). It only ever
 * turns false into true, so the carriage's edges still stop a strafe off the side.</p>
 */
@Mixin(MoveControl.class)
public abstract class MoveControlCarriageWalkableMixin {

    @Shadow
    @Final
    protected Mob mob;

    @ModifyReturnValue(method = "isWalkable(FF)Z", at = @At("RETURN"))
    private boolean dungeontrain$walkableOnCarriage(boolean walkable, float relativeX, float relativeZ) {
        if (walkable || this.mob.level().isClientSide()) return walkable;
        PathNavigation navigation = this.mob.getNavigation();
        return navigation != null
                && CarriageMobAi.walkableOnCarriage(this.mob, navigation.getNodeEvaluator(), relativeX, relativeZ);
    }
}
