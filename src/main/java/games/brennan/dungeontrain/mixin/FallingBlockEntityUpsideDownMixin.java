package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.UpsideDownGravity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes a {@link FallingBlockEntity} fall <b>up</b> inside the upside-down band (and its entry lead-in),
 * where the mirrored terrain hangs overhead as the ceiling.
 *
 * <ul>
 *   <li>{@code getDefaultGravity} is negated, so {@code applyGravity} accelerates the block +Y. Runs on
 *       both sides — the client simulates the flight between position syncs — via
 *       {@link UpsideDownGravity#isReversedEntity}.</li>
 *   <li>The landing check in {@code tick} ({@code onGround()}) becomes "hit something above": vanilla's
 *       {@code onGround} only latches on a downward collision.</li>
 *   <li>The "would it keep falling?" support probe in {@code tick} ({@code blockpos.below()}) looks above.</li>
 * </ul>
 * Rising past the build limit is already covered by vanilla's {@code y > maxBuildHeight} discard.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityUpsideDownMixin {

    private boolean dungeontrain$reversed() {
        Entity self = (Entity) (Object) this;
        return UpsideDownGravity.isReversedEntity(self.level(), self.getBlockX());
    }

    @ModifyReturnValue(method = "getDefaultGravity", at = @At("RETURN"))
    private double dungeontrain$fallUp(double gravity) {
        return dungeontrain$reversed() ? -gravity : gravity;
    }

    @WrapOperation(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;onGround()Z"))
    private boolean dungeontrain$landOnCeiling(FallingBlockEntity self, Operation<Boolean> original) {
        if (dungeontrain$reversed()) return self.verticalCollision && !self.verticalCollisionBelow;
        return original.call(self);
    }

    @WrapOperation(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;"))
    private BlockPos dungeontrain$supportAbove(BlockPos pos, Operation<BlockPos> original) {
        if (dungeontrain$reversed()) return UpsideDownGravity.supportPos(pos, true);
        return original.call(pos);
    }
}
