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
 * Issue #646 — makes a DT-frozen carriage safe to leave in the resident list with its Rapier body
 * removed. Sable steps every resident sub-level; two of its per-body methods reach native Rapier
 * memory guarded only by {@code isRemoved()}, which is still false after a {@code pipeline.remove()}.
 * This mixin (a) carries DT's per-instance freeze flag on {@link ServerSubLevel} via
 * {@link DtFreezable}, and (b) skips those two native readers when the flag is set.
 *
 * <p>Gated readers (bytecode-verified against {@code sable-2.0.2+mc1.21.1}):
 * {@code prePhysicsTick} (native velocity read) and {@code applyQueuedForces} (native force write).
 * The third native reader, {@code SubLevelPhysicsSystem.updatePose} (pose read), is gated in
 * {@link SubLevelPhysicsSystemFreezeMixin}. The remaining {@code getAllSubLevels()} loops
 * ({@code updateLastPose}/{@code prePhysicsTickBegin}/{@code updateMergedMassData}) touch no native
 * memory and are intentionally left alone. <b>Re-enumerate this set on any {@code sable_version}
 * bump.</b></p>
 *
 * <p>{@code remap = false}: the target and its methods are Sable's own names, not Minecraft
 * mappings.</p>
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelFreezeMixin implements DtFreezable {

    @Unique private boolean dungeonTrain$physicsFrozen;
    @Unique private int dungeonTrain$inactiveTicks;
    @Unique private boolean dungeonTrain$inScene;

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

    @Override
    public boolean dt$isInScene() {
        return dungeonTrain$inScene;
    }

    @Override
    public void dt$setInScene(boolean inScene) {
        this.dungeonTrain$inScene = inScene;
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
