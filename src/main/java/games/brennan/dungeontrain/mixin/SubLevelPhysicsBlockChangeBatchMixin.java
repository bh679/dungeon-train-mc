package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.ColliderBatch;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Defers the voxel-collider half of Sable's per-block-change handling while a
 * {@link ColliderBatch} scope is open — see that class for the why and the rebuild.
 *
 * <p>Two wraps inside {@code SubLevelPhysicsSystem.handleBlockChange(SectionPos, LevelChunkSection,
 * int, int, int, BlockState, BlockState)}: the {@code INVOKEINTERFACE} of
 * {@code PhysicsPipeline.handleBlockChange} (the seven native {@code changeBlock} calls) and the
 * {@code INVOKEVIRTUAL} of {@code wakeUpObjectsAt(III)}. Everything else in the method — the ticket
 * registration of a plot section, {@code updateMassDataFromBlockChange} with its
 * {@code onStatsChanged} push — runs untouched, so mass, centre of mass and the pivot pin behave
 * exactly as before. Outside a scope both wraps call straight through.</p>
 *
 * <p>{@code remap = false}: Sable's own class and names; the vanilla types in the target descriptors
 * are the runtime (Mojang-mapped) names Sable's bytecode itself references. Bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code handleBlockChange} contains exactly one call to each target.
 * <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsBlockChangeBatchMixin {

    @WrapOperation(
            method = "handleBlockChange",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/PhysicsPipeline;handleBlockChange(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"
            )
    )
    private void dungeontrain$deferColliderUpdate(final PhysicsPipeline pipeline, final SectionPos sectionPos,
                                                  final LevelChunkSection section, final int x, final int y, final int z,
                                                  final BlockState oldState, final BlockState newState,
                                                  final Operation<Void> original) {
        if (ColliderBatch.isActive()) {
            ColliderBatch.record(((SubLevelPhysicsSystem) (Object) this).getLevel(), sectionPos, section);
            return;
        }
        original.call(pipeline, sectionPos, section, x, y, z, oldState, newState);
    }

    @WrapOperation(
            method = "handleBlockChange",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;wakeUpObjectsAt(III)V"
            )
    )
    private void dungeontrain$deferWakeUp(final SubLevelPhysicsSystem self, final int x, final int y, final int z,
                                          final Operation<Void> original) {
        if (ColliderBatch.isActive()) return; // one wake-up per section at rebuild instead
        original.call(self, x, y, z);
    }
}
