package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import games.brennan.dungeontrain.ship.sable.ProtrudingShapeClip;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Feeds Sable's sub-level collision sweep the clipped shapes from {@link ProtrudingShapeClip}, so
 * a fence or wall post under a trapdoor (or carpet, or slab) no longer pokes into the feet of an
 * entity standing on the trapdoor and jitters it via sideways push + step-up.
 *
 * <p>Both the main MTV pass ({@code getSubLevelEntityCollisionShape}) and the step-up probe
 * ({@code hasCollision}) are wrapped so they agree on the geometry; the scaffolding branch's
 * three-argument {@code getCollisionShape} overload is deliberately left alone.</p>
 *
 * <p>{@code remap = false}: the target class and both method names are Sable's own. The wrapped
 * call is a vanilla name, which is what the runtime uses on NeoForge 1.21.1 anyway (same pattern as
 * {@link LevelAcceleratorNoSyncLoadMixin}). Bytecode-verified against {@code sable-2.0.5+mc1.21.1}
 * (each method has exactly one two-argument {@code getCollisionShape} call).
 * <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionShapeClipMixin {

    @WrapOperation(
            method = {"getSubLevelEntityCollisionShape", "hasCollision"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private static VoxelShape dungeontrain$clipProtrudingShape(
            final BlockState state,
            final BlockGetter getter,
            final BlockPos pos,
            final Operation<VoxelShape> original
    ) {
        return ProtrudingShapeClip.clip(state, getter, pos, original.call(state, getter, pos));
    }
}
