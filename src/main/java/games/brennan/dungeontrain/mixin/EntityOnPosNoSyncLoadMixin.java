package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.ship.sable.OnPosFallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

/**
 * Keeps {@code Entity.getOnPos(float)} from handing out a block position in an unloaded chunk on
 * the server, so its callers' {@code level.getBlockState} never sync-loads one.
 *
 * <p><b>Why:</b> Sable's {@code effects.EntityMixin#sable$preGetOnPos} ({@code @Inject HEAD,
 * cancellable}) returns a <i>plot-space</i> position (x ≈ 20,480,000) for an entity tracking a
 * sub-level: its feet transformed by {@code logicalPose().transformPositionInverse}. Vanilla then
 * reads friction, speed/jump factor, fall-damage and step-sound blocks there. When that plot chunk
 * wasn't loaded the server thread generated it inline: a 23.5 s freeze in a player's /bug report
 * ({@code Villager.tick → LivingEntity.travel → Level.getBlockState → ServerChunkCache.getChunk →
 * managedBlock}). With no tracking sub-level and no supporting block, Sable also scans every
 * intersecting sub-level and calls {@code level.getBlockState} on plot positions itself.</p>
 *
 * <p><b>How:</b> {@code @WrapMethod} wraps the whole method, Sable's injection included, so it does
 * not depend on injector order. (1) Before Sable's scan branch runs, the same plot positions are
 * worked out without reading blocks; if any lands in an unloaded chunk the vanilla unsupported
 * answer is returned instead. (2) After the call, a result in an unloaded chunk is swapped for the
 * vanilla answer (a world-space block under the entity, beside its own ticking chunk). Every chunk
 * check is {@link ServerChunkCache#getChunkNow}, which never loads. A mob on a loaded carriage gets
 * Sable's answer untouched, so carriage-floor friction and sounds are unchanged.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.2+mc1.21.1}. <b>Re-verify on any
 * {@code sable_version} bump</b>: the scan pre-check mirrors {@code sable$preGetOnPos} (feet via
 * {@code getFeetPos(e, max(0.1, yOffset))}, box {@code BoundingBox3d(blockPosition).expand(yOffset)}).</p>
 */
@Mixin(Entity.class)
public abstract class EntityOnPosNoSyncLoadMixin {

    @Shadow
    public Optional<BlockPos> mainSupportingBlockPos;

    @Shadow
    private BlockPos blockPosition;

    @Shadow
    private Vec3 position;

    @Shadow
    public abstract Level level();

    @WrapMethod(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;")
    private BlockPos dungeontrain$onPosNoSyncLoad(final float yOffset, final Operation<BlockPos> original) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return original.call(yOffset);
        }
        final ServerChunkCache chunks = serverLevel.getChunkSource();
        final Entity self = (Entity) (Object) this;

        if (this.mainSupportingBlockPos.isEmpty() && Sable.HELPER.getTrackingSubLevel(self) == null
                && !this.dungeontrain$scanTargetsLoaded(serverLevel, chunks, self, yOffset)) {
            return OnPosFallback.unsupported(this.position.x, this.position.y, this.position.z, yOffset);
        }

        final BlockPos pos = original.call(yOffset);
        if (OnPosFallback.chunkLoadedFor(pos, key -> dungeontrain$loaded(chunks, key))) {
            return pos;
        }
        return this.dungeontrain$vanillaOnPos(chunks, yOffset);
    }

    /** The plot positions Sable's intersecting scan would read, each checked without loading. */
    private boolean dungeontrain$scanTargetsLoaded(final ServerLevel level, final ServerChunkCache chunks,
                                                   final Entity self, final float yOffset) {
        final BoundingBox3d box = new BoundingBox3d(this.blockPosition);
        box.expand(yOffset, box);
        final Vector3d feet = Sable.HELPER.getFeetPos(self, Math.max(0.1F, yOffset));
        for (final SubLevel sub : Sable.HELPER.getAllIntersecting(level, box)) {
            final Vector3d local = sub.logicalPose().transformPositionInverse(feet, new Vector3d());
            final BlockPos plotPos = BlockPos.containing(local.x, local.y, local.z);
            if (!OnPosFallback.chunkLoadedFor(plotPos, key -> dungeontrain$loaded(chunks, key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Vanilla {@code Entity.getOnPos(float)}, 1.21.1 NeoForge, copied as is, except that a supporting
     * block in an unloaded chunk is ignored rather than read, so this can't load one either.
     */
    private BlockPos dungeontrain$vanillaOnPos(final ServerChunkCache chunks, final float yOffset) {
        if (this.mainSupportingBlockPos.isPresent()
                && OnPosFallback.chunkLoadedFor(this.mainSupportingBlockPos.get(), key -> dungeontrain$loaded(chunks, key))) {
            final BlockPos blockpos = this.mainSupportingBlockPos.get();
            if (!(yOffset > 1.0E-5F)) {
                return blockpos;
            }
            final BlockState blockstate = this.level().getBlockState(blockpos);
            return (!((double) yOffset <= 0.5) || !blockstate.collisionExtendsVertically(this.level(), blockpos, (Entity) (Object) this))
                    ? blockpos.atY(Mth.floor(this.position.y - (double) yOffset))
                    : blockpos;
        }
        return OnPosFallback.unsupported(this.position.x, this.position.y, this.position.z, yOffset);
    }

    private static boolean dungeontrain$loaded(final ServerChunkCache chunks, final long key) {
        return chunks.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null;
    }
}
