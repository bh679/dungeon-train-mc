package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import dev.ryanhcode.sable.util.LevelAccelerator;
import games.brennan.dungeontrain.ship.sable.NoSyncLoadChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Opts the entity-collision sweep's {@link LevelAccelerator} into <b>non-loading</b> chunk
 * access (see {@link LevelAcceleratorNoSyncLoadMixin}).
 *
 * <p>{@code SubLevelEntityCollision.collide} builds one throwaway
 * {@code new LevelAccelerator(level)} per sweep and uses it purely for block/fluid/shape
 * reads across every intersecting sub-level (including {@code tryStepUp}/{@code hasCollision}).
 * When that sweep touched an unloaded chunk — e.g. a dropped item colliding against a train
 * plot chunk whose expensive worldgen hadn't run yet — the accelerator's fallback
 * {@code Level.getChunk(x, z)} synchronously loaded/generated the chunk on the server thread,
 * freezing the whole server for multiple seconds to minutes (stall-watchdog stack:
 * {@code ItemEntity.tick → Entity.move → sable$collideRedirect → collide →
 * LevelAccelerator.getBlockState → ServerChunkCache.getChunkFutureMainThread}).</p>
 *
 * <p>Flagging the instance here (rather than a global toggle) keeps every other
 * {@code LevelAccelerator} user in Sable — notably {@code setBlockFast} writes — on the
 * vanilla loading path. Real players never reach this sweep ({@code collide} short-circuits
 * {@code ServerPlayer} at the top), so treating missing chunks as air only affects items and
 * mobs, which may fall through not-yet-loaded train geometry instead of stalling the server.
 * Applies to all sub-levels in the sweep, DT-managed or not — the single accelerator serves
 * them all, and non-blocking collision is the sane behaviour universally.</p>
 *
 * <p>{@code remap = false}: target class, {@code collide}, and the {@code LevelAccelerator}
 * constructor are Sable's own names. Bytecode-verified against {@code sable-2.0.2+mc1.21.1}
 * ({@code collide} has exactly one {@code new LevelAccelerator} site). <b>Re-verify on any
 * {@code sable_version} bump.</b> Mirrors the gating pattern of
 * {@link SubLevelHeatMapSplitMixin} / {@link ServerSubLevelFreezeMixin}.</p>
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionNoLoadMixin {

    @ModifyExpressionValue(
            method = "collide",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/level/Level;)Ldev/ryanhcode/sable/util/LevelAccelerator;"
            )
    )
    private static LevelAccelerator dungeontrain$flagCollisionAcceleratorNoSyncLoad(final LevelAccelerator accel) {
        ((NoSyncLoadChunkAccess) (Object) accel).dungeontrain$setNoSyncLoad(true);
        return accel;
    }
}
