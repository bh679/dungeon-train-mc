package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.DtFreezable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Issue #646 (soft-freeze) — carries DT's per-instance freeze flag on {@link ServerSubLevel} via
 * {@link DtFreezable}, and skips two of Sable's per-body steps for a frozen (parked) carriage. Sable
 * steps every resident sub-level; a frozen carriage stays resident and IN the scene, but there is no
 * point running its per-body work when DT has parked it — skipping it is the soft-freeze saving.
 *
 * <p>Gated steps (bytecode-verified against {@code sable-2.0.2+mc1.21.1}): {@code prePhysicsTick}
 * (per-body velocity read) and {@code applyQueuedForces} (per-body force write). The third native
 * per-body reader, {@code SubLevelPhysicsSystem.updatePose} (pose read), is gated in
 * {@link SubLevelPhysicsSystemFreezeMixin}. The remaining {@code getAllSubLevels()} loops
 * ({@code updateLastPose}/{@code prePhysicsTickBegin}/{@code updateMergedMassData}) are cheap and left
 * alone. Because the body is never removed, skipping (or not) is only a perf question, never a crash
 * one. <b>Re-enumerate this set on any {@code sable_version} bump.</b></p>
 *
 * <p>{@code remap = false}: the target and its methods are Sable's own names, not Minecraft
 * mappings.</p>
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelFreezeMixin implements DtFreezable {

    @Unique private boolean dungeonTrain$physicsFrozen;
    @Unique private int dungeonTrain$inactiveTicks;

    @Override
    public boolean dt$isPhysicsFrozen() {
        return dungeonTrain$physicsFrozen;
    }

    @Override
    public void dt$setPhysicsFrozen(boolean frozen) {
        this.dungeonTrain$physicsFrozen = frozen;
    }

    @Override
    public int dt$inactiveTicks() {
        return dungeonTrain$inactiveTicks;
    }

    @Override
    public void dt$setInactiveTicks(int ticks) {
        this.dungeonTrain$inactiveTicks = ticks;
    }

    @Inject(method = "prePhysicsTick", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$skipFrozenPrePhysicsTick(SubLevelPhysicsSystem system,
                                                       RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (dungeonTrain$physicsFrozen) ci.cancel();
    }

    @Inject(method = "applyQueuedForces", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$skipFrozenApplyQueuedForces(SubLevelPhysicsSystem system,
                                                          RigidBodyHandle handle, double dt, CallbackInfo ci) {
        if (dungeonTrain$physicsFrozen) ci.cancel();
    }
}
