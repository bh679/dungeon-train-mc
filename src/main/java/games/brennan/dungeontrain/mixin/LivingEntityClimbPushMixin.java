package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import games.brennan.dungeontrain.ship.sable.SableClimbPush;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops a moving carriage's ladder hauling a rider up by itself.
 *
 * <p>{@code LivingEntity.handleRelativeFrictionAndCalculateMovement} climbs when
 * {@code (horizontalCollision || jumping) && onClimbable()}. On a moving Sable sub-level the
 * collision flag is set every tick a wall catches up with an off-ground entity, so a player who
 * steps onto a ladder rises with no input. This swaps the one {@code horizontalCollision} read in
 * that method for {@link SableClimbPush#countsAsPush}, which honours a ship push only when the
 * entity's own motion was into the pushing block — see that class for the mechanism.</p>
 *
 * <p>Common side on purpose: a player's movement is simulated on the client, a mob's on the
 * server, and both run this method. Sable short-circuits {@code ServerPlayer} in its collide, so
 * the server-side player never carries a sub-level push and stays on the vanilla path.</p>
 *
 * <p>Verified against MC 1.21.1 / NeoForge 21.1.228 (exactly one {@code horizontalCollision}
 * read in the method) and Sable {@code 2.0.5+mc1.21.1} ({@code CollisionInfo} fields).
 * <b>Re-verify on any MC, NeoForge or {@code sable_version} bump.</b> {@code require = 1} so a
 * mis-target fails the game load rather than silently reviving the bug.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClimbPushMixin {

    @ModifyExpressionValue(
            method = "handleRelativeFrictionAndCalculateMovement",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "Lnet/minecraft/world/entity/LivingEntity;horizontalCollision:Z"
            ),
            require = 1
    )
    private boolean dungeontrain$shipPushIsNotClimbInput(boolean horizontalCollision) {
        return SableClimbPush.countsAsPush(
                horizontalCollision,
                ((EntityMovementExtension) this).sable$getCollisionInfo());
    }
}
