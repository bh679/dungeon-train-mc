package games.brennan.dungeontrain.mixin.client.vivecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Makes Vivecraft VR melee work while standing on a Sable sub-level (train carriage), and — as a
 * side effect — stops the render-thread log-spam that the broken query produces.
 *
 * <p><b>The bug.</b> Vivecraft's {@code SwingTracker.activeProcess} broad-phases melee candidates
 * with {@code mc.level.getEntities(mc.player, swingAABB)}. When the player rides a Sable sub-level,
 * the swing AABB is built from a mix of the player's real (render) position and the player entity's
 * stored (sub-level logical, ~2.048e7) position, so it spans ~20 million blocks. Sable's
 * {@code SubLevelInclusiveLevelEntityGetter} rejects any query with
 * {@code AABB.getSize() > 100000}: it logs an ERROR with a captured stack trace and returns
 * <em>nothing</em>. Result: the VR swing finds no targets (melee silently fails on the train) and the
 * per-call stack-trace capture hitches the render thread.</p>
 *
 * <p><b>The fix.</b> This wrap detects the pathologically-large swing AABB and rebuilds it as a small
 * box centred on the player's own bounding box, inflated to VR melee reach. That box is in the
 * player's current coordinate frame (the same frame as the on-carriage mobs at tick time), so Sable's
 * getter resolves it against the sub-level and returns the nearby mobs as candidates instead of
 * aborting. Vivecraft's own per-entity swing test then runs unchanged on those candidates, so hit
 * precision is preserved — we only repair the broad-phase box.</p>
 *
 * <p>An absurdly large AABB is the unambiguous signature of the sub-level coordinate mismatch: a real
 * VR swing box is only a few blocks across, so {@link #MAX_SANE_SWING_SIZE} is far above any
 * legitimate query yet far below the mismatch. Using that as the trigger keeps the wrap a zero-cost
 * pass-through in every normal (non-train / non-VR) case and avoids per-tick sub-level lookups on the
 * render thread.</p>
 *
 * <p>Applied only when Vivecraft is present (see {@code VivecraftMixinPlugin}); the class is targeted
 * by string because Vivecraft is not a compile dependency. Scope is deliberately narrow: VR
 * melee-on-train only — no locomotion, ray-casts, GUI or seated play.</p>
 */
@Mixin(targets = "org.vivecraft.client_vr.gameplay.trackers.SwingTracker", remap = false)
public abstract class SwingTrackerSubLevelAabbMixin {

    /**
     * Average side length ({@link AABB#getSize()}) above which a swing query is treated as the
     * sub-level coordinate mismatch. Comfortably above any real melee box (a few blocks) and far
     * below both Sable's 100000 abort threshold and the ~6.8M produced by the mismatch.
     */
    private static final double MAX_SANE_SWING_SIZE = 256.0;

    /** VR melee reach (blocks) to inflate the player's bounding box by when rebuilding the box. */
    private static final double MELEE_REACH = 4.0;

    @WrapOperation(
        method = "activeProcess",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;"
                   + "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)"
                   + "Ljava/util/List;"))
    private List<Entity> dungeontrain$clampSwingAabbToSubLevel(
            ClientLevel level, Entity entity, AABB area, Operation<List<Entity>> original) {
        if (entity != null && area.getSize() > MAX_SANE_SWING_SIZE) {
            AABB clamped = entity.getBoundingBox().inflate(MELEE_REACH);
            return original.call(level, entity, clamped);
        }
        return original.call(level, entity, area);
    }
}
