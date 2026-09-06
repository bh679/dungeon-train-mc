package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import games.brennan.dungeontrain.ship.sable.SableHoldingIndex;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every sub-level Sable files into holding, so Dungeon Train knows what is on disk even
 * after Sable's in-memory record of it has been evicted. See {@link SableHoldingIndex} for why
 * that matters — in short, {@code SubLevelHoldingChunkMap.saveAll()} drops hidden holding chunks
 * from {@code allHoldingSubLevels} while leaving the data on disk, which made
 * {@code SableShipyard.isHeld} report a merely-sleeping carriage group as gone for good.
 *
 * <p><b>Why this method and not {@code moveToUnloaded}.</b> Bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code moveToUnloaded} is called only from
 * {@code PhysicsChunkTicketManager}, so it misses the chunk-status cull entirely — the private
 * {@code SubLevelHoldingChunkMap.processUnload} files to holding inline without going through it.
 * {@code acceptHoldingSubLevel} is the one method all three filing paths share (the physics
 * cull, the chunk-status cull, and re-materialising a chunk from disk), it is referenced by no
 * class outside {@code SubLevelHoldingChunkMap}, and each of those call sites is immediately
 * followed by {@code allHoldingSubLevels.put}. The chunk also knows its own position, so the
 * {@link ChunkPos} comes from the authoritative owner rather than a caller's argument.</p>
 *
 * <p>{@code @At("HEAD")} is safe: the method body is a single unconditional map put with no
 * failure path. The write also lands strictly before the matching
 * {@code container.removeSubLevel(..., UNLOADED)}, so no tick-instant has a group missing from
 * both {@code findAll()} and the index.</p>
 *
 * <p><b>Re-verify both the target and the caller set on any {@code sable_version} bump.</b> This
 * mixin is deliberately left {@code required} — a silent no-op after a Sable change would restore
 * the duplicate-carriage bug with no log line to explain it.</p>
 *
 * <p>{@code remap = false}: the target and its methods are Sable's own names, not Minecraft
 * mappings.</p>
 */
@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public abstract class SubLevelHoldingChunkFileMixin {

    @Shadow
    public abstract ChunkPos getChunkPos();

    @Inject(method = "acceptHoldingSubLevel", at = @At("HEAD"))
    private void dungeonTrain$recordFiled(HoldingSubLevel held, CallbackInfo ci) {
        if (held == null) return;
        SubLevelData data = held.data();
        if (data == null || data.uuid() == null) return;
        SableHoldingIndex.filed(data.uuid(), getChunkPos());
    }
}
