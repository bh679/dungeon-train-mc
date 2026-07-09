package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/**
 * Shared helpers for the train / track / tunnel carving hot paths that must
 * clear or replace blocks without spawning particles, playing break sounds,
 * or dropping items.
 *
 * <p>{@code Level.destroyBlock(pos, false)} still fires {@code levelEvent(2001,
 * ...)}  — break particles + sound — so it's not silent. Plain
 * {@code Level.setBlock(pos, state, UPDATE_CLIENTS)} is silent for regular
 * terrain, but when the replaced block has a block entity (chest, hopper,
 * furnace, brewing stand, barrel, shulker box — all common in villages,
 * mineshafts, strongholds, dungeons) vanilla {@code BlockBehaviour.onRemove}
 * overrides still drop container contents as item entities.</p>
 *
 * <p>{@link #setBlockSilent} removes the block entity first so the
 * {@code onRemove} subclass overrides see no container, then writes the new
 * state with {@link Block#UPDATE_CLIENTS} | {@link Block#UPDATE_SUPPRESS_DROPS}.
 * The shape-update cascade still runs, which keeps fences/rails/redstone
 * properly connected to neighbours after placement.</p>
 *
 * <p>{@link #setBlockSilentNoCascade} additionally sets
 * {@link Block#UPDATE_KNOWN_SHAPE}, which short-circuits the shape-update
 * cascade entirely (see {@code Level.markAndNotifyBlock}). This is the only
 * way to fully suppress plant drops during in-place block swaps: plants
 * ({@code BushBlock} subclasses — saplings, flowers, tall grass) whose
 * {@code updateShape} returns {@code AIR} for a missing substrate are
 * routed through {@code Block.updateOrDestroy → level.destroyBlock(…, dropBlock=true)},
 * and the subFlags computed by {@code markAndNotifyBlock} strip
 * {@code UPDATE_SUPPRESS_DROPS} ({@code flags & ~(UPDATE_NEIGHBORS | UPDATE_SUPPRESS_DROPS)}),
 * so the suppress flag does not reach the cascade. Skipping the cascade
 * outright is the only reliable fix. Use this variant in the editor
 * variant preview ticker that cycles cells through plant entries.</p>
 */
public final class SilentBlockOps {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private SilentBlockOps() {}

    /**
     * Replace the block at {@code pos} with {@code newState} silently — no
     * break particles, no break sound, no item drops, no container spill.
     * Callers are responsible for all other guards (ship-owned positions,
     * chunk loaded, idempotence).
     */
    public static void setBlockSilent(ServerLevel level, BlockPos pos, BlockState newState) {
        BlockState existing = level.getBlockState(pos);
        if (existing.hasBlockEntity()) {
            level.removeBlockEntity(pos);
        }
        level.setBlock(pos, newState, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
    }

    /**
     * Variant that also seeds the new block entity from {@code beNbt}. When
     * {@code newState} has no BlockEntity or {@code beNbt} is null, this is
     * equivalent to {@link #setBlockSilent(ServerLevel, BlockPos, BlockState)}.
     *
     * <p>Used by the variants v2 placement path so block-entity candidates
     * (chests, signs, banners, …) round-trip with their NBT contents.</p>
     */
    public static void setBlockSilent(ServerLevel level, BlockPos pos, BlockState newState, @Nullable CompoundTag beNbt) {
        setBlockSilent(level, pos, newState);
        if (beNbt == null || !newState.hasBlockEntity()) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        // Stamp the BE's coordinate fields so load() doesn't overwrite the
        // freshly-placed position. Vanilla BlockEntity.load reads x/y/z from
        // the tag if present, so we set them to match the target pos.
        CompoundTag positioned = beNbt.copy();
        positioned.putInt("x", pos.getX());
        positioned.putInt("y", pos.getY());
        positioned.putInt("z", pos.getZ());
        be.loadWithComponents(positioned, level.registryAccess());
        be.setChanged();
    }

    /** Clear the block at {@code pos} to air silently. */
    public static void clearBlockSilent(ServerLevel level, BlockPos pos) {
        setBlockSilent(level, pos, AIR);
    }

    /**
     * Stamp {@code state} at {@code pos} the cheapest possible way: a raw
     * write into the {@link LevelChunkSection} palette with
     * {@code lightUpdate = false} — <b>no</b> light-engine {@code checkBlock},
     * <b>no</b> neighbour-shape cascade, <b>no</b> {@code LevelChunk.setBlockState}
     * bookkeeping (heightmap / POI / block-entity ticking / Sable's
     * {@code LevelChunk.setBlockState} physics-neighbourhood mixin). Only a
     * batched client {@code blockChanged} + {@code setUnsaved} follow. This is
     * the corridor-cleanup / bedrock-floor path (see
     * {@code TrackGenerator.writeTrackCell} and {@code BedrockFloorEvents}),
     * lifted here so the carriage-spawn placement can reuse it.
     *
     * <p><b>Use only for blocks with no {@link BlockState#hasBlockEntity() block
     * entity}</b> — a section write does not instantiate the BE, so a container
     * would come out empty. Route BE cells through
     * {@link #setBlockSilent(ServerLevel, BlockPos, BlockState, CompoundTag)}.
     * Because it skips the shape cascade it also skips lighting/heightmap
     * upkeep; that is acceptable where the written blocks are ephemeral (a
     * carriage placed in the world only to be lifted into a Sable sub-level the
     * same tick, which re-airs the source cells) or where stale light on
     * removed blocks is tolerated (corridor cleanup).</p>
     *
     * <p><b>Concurrency (CRITICAL).</b> The underlying
     * {@code section.setBlockState(x,y,z,state,false)} is the lock-<i>skipping</i>
     * {@code getAndSetUnchecked} write: it grows the section's {@code LinearPalette}
     * <i>in place</i>. The block-light engine reads sections lock-free
     * ({@code PalettedContainer.get}) from chunk-gen <b>worker</b> threads, so if
     * this write runs while such a worker is reading the same section, the reader
     * can observe the new storage index before the palette's {@code size} grows and
     * throw {@link net.minecraft.world.level.chunk.MissingPaletteEntryException},
     * failing the chunk future and stalling world-gen (a server-tick soft-hang).
     * Passing {@code useLocks=true} does <b>not</b> fix this — the reader never
     * locks, so vanilla's {@code ThreadingDetector} never engages against it.
     * <b>Callers must therefore guarantee the target column AND its 1-chunk
     * neighbours are {@code FULL}</b> (no async light task can then reach this
     * column) before calling — e.g. {@code TrainCarriageAppender.
     * ensureSpawnFootprintReady} / {@code prewarmEagerFillChunks}, which gate on a
     * footprint±1 quiescence region. The {@code getChunkNow == null} fallback below
     * is a belt-and-braces guard, not that guarantee.</p>
     *
     * @return {@code true} if written section-local; {@code false} if the chunk
     *     was not loaded, in which case it falls back to
     *     {@link #setBlockSilent(ServerLevel, BlockPos, BlockState)} (which
     *     relights) so the write is never silently dropped.
     */
    public static boolean setBlockSectionLocal(ServerLevel level, BlockPos pos, BlockState state) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            // Not loaded — cannot section-write; fall back to the (relighting)
            // silent path rather than drop the block. Rare for carriage spawns:
            // TrainCarriageAppender defers a spawn until its footprint chunks are
            // loaded to FULL (see ensureSpawnFootprintReady), so this is a
            // belt-and-braces guard, not the normal path.
            setBlockSilent(level, pos, state);
            return false;
        }
        setBlockSectionLocal(level, chunk, pos, state);
        return true;
    }

    /**
     * {@link #setBlockSectionLocal(ServerLevel, BlockPos, BlockState)} with a
     * caller-resolved {@code chunk} — use when writing many cells in the same
     * chunk column so the chunk lookup isn't repeated per cell. {@code chunk}
     * must be the {@link LevelChunk} containing {@code pos}.
     */
    public static void setBlockSectionLocal(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state) {
        int sIdx = chunk.getSectionIndex(pos.getY());
        LevelChunkSection section = chunk.getSection(sIdx);
        int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        int ly = pos.getY() - baseY;
        // Drop any pre-existing BE so a stale container from a prior life of the
        // cell doesn't linger under the new (BE-less) state.
        if (section.getBlockState(lx, ly, lz).hasBlockEntity()) chunk.removeBlockEntity(pos);
        section.setBlockState(lx, ly, lz, state, false);
        chunk.setUnsaved(true);
        level.getChunkSource().blockChanged(pos);
    }

    /**
     * Like {@link #setBlockSilent(ServerLevel, BlockPos, BlockState, CompoundTag)}
     * but additionally sets {@link Block#UPDATE_KNOWN_SHAPE} so the
     * neighbour shape-update cascade is skipped. Required for in-place
     * swaps that include plant blocks ({@code BushBlock} subclasses): the
     * cascade otherwise destroys plants whose substrate or paired half is
     * (or appears) missing and drops their items as side effects, and the
     * cascade subFlags strip {@link Block#UPDATE_SUPPRESS_DROPS} so that
     * flag alone cannot stop those drops.
     *
     * <p>Caller-visible trade-off: adjacent blocks whose visual shape
     * depends on this position (fences connecting, redstone wires
     * re-routing, rails curving) will <b>not</b> re-evaluate after the
     * swap. Only use this when the swap is a transient preview / editor
     * cycle frame where neighbour visual updates do not matter.</p>
     */
    public static void setBlockSilentNoCascade(ServerLevel level, BlockPos pos, BlockState newState, @Nullable CompoundTag beNbt) {
        BlockState existing = level.getBlockState(pos);
        if (existing.hasBlockEntity()) {
            level.removeBlockEntity(pos);
        }
        level.setBlock(pos, newState,
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        if (beNbt == null || !newState.hasBlockEntity()) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        CompoundTag positioned = beNbt.copy();
        positioned.putInt("x", pos.getX());
        positioned.putInt("y", pos.getY());
        positioned.putInt("z", pos.getZ());
        be.loadWithComponents(positioned, level.registryAccess());
        be.setChanged();
    }
}
