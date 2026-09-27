package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.ship.Shipyards;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.IntPredicate;

/**
 * Gravity rules for the upside-down band. Shared by the block-tick mixins
 * ({@code FallingBlockUpsideDownMixin}, {@code BrushableBlockUpsideDownMixin}) and the entity mixin
 * ({@code FallingBlockEntityUpsideDownMixin}) so the three can never disagree about which way is down.
 *
 * <ul>
 *   <li><b>Frozen</b> ({@link #isFrozen}): a chunk that has generated but whose deferred mirror hasn't
 *       applied yet ({@code NEEDS_UPSIDE_DOWN_MIRROR}) is live and ticking, so worldgen's persisted
 *       sand/gravel ticks would drop blocks through the <em>un-flipped</em> terrain. Gravity is off there
 *       until the flip lands.</li>
 *   <li><b>Reversed</b> ({@link #isReversedBlock} / {@link #isReversedEntity}): inside the band and its
 *       entry lead-in — the same zone the client renders flipped — gravity points +Y, so gravity blocks
 *       fall up onto the reflected ceiling.</li>
 * </ul>
 */
public final class UpsideDownGravity {

    /**
     * Gravity ticks swallowed while their chunk waited for the mirror, keyed by chunk — exactly the blocks
     * vanilla would have dropped. {@code UpsideDownMirror.armFallUp} replays each at its <em>mirrored</em>
     * position once the flip lands. Server-thread only; in-memory (a chunk that unloads first just loses
     * them, and its blocks stay put — vanilla's own behaviour for an un-ticked gravity block).
     */
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> FROZEN = new Long2ObjectOpenHashMap<>();

    /**
     * Client-side band test (world-X → in band or entry lead-in). Installed by {@code DungeonTrainClient}
     * at client setup so this common class never touches client-only classes; a dedicated server keeps
     * the {@code false} default and only ever takes the server branch.
     */
    private static volatile IntPredicate clientBand = x -> false;

    private UpsideDownGravity() {}

    /** Remember a gravity tick swallowed at {@code pos} while its chunk is frozen. */
    public static void recordFrozen(BlockPos pos) {
        FROZEN.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new LongOpenHashSet())
            .add(pos.asLong());
    }

    /** Take (and forget) every tick recorded for a chunk; empty when none. */
    public static LongOpenHashSet drainFrozen(long chunkKey) {
        LongOpenHashSet set = FROZEN.remove(chunkKey);
        return set != null ? set : new LongOpenHashSet();
    }

    /** Forget a chunk's recorded ticks (unload). */
    public static void forgetFrozen(long chunkKey) {
        FROZEN.remove(chunkKey);
    }

    /** Forget everything (overworld unload), so no tick leaks into the next world. */
    public static void clearFrozen() {
        FROZEN.clear();
    }

    public static void setClientBand(IntPredicate band) {
        clientBand = band;
    }

    /** The cell a gravity block rests on: above it when gravity is reversed, below it otherwise. */
    public static BlockPos supportPos(BlockPos pos, boolean reversed) {
        return reversed ? pos.above() : pos.below();
    }

    /**
     * True iff a gravity block at {@code pos} falls up: overworld, band or entry lead-in, and not a
     * Sable ship voxel (plot coordinates wrap into the band — the same trap {@code UpsideDownRenderFlip}
     * guards on the client).
     */
    public static boolean isReversedBlock(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        if (!UpsideDownBand.isInBandOrEntryLead(level, pos.getX())) return false;
        return !Shipyards.of(level).isInShip(pos);
    }

    /**
     * True iff a falling-block entity at world-X {@code x} falls up. Evaluated on both sides so the
     * client animates the rise the server simulates. Entities are world-space (never plot-space), so no
     * ship guard is needed.
     */
    public static boolean isReversedEntity(Level level, int x) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        if (level instanceof ServerLevel server) return UpsideDownBand.isInBandOrEntryLead(server, x);
        return level.isClientSide() && clientBand.test(x);
    }

    /**
     * True iff gravity is suspended at {@code pos} because its chunk is still waiting for the deferred
     * upside-down mirror. The marker is only ever set on band / lead-in / exit-fade chunks, so its
     * presence alone is the gate.
     */
    public static boolean isFrozen(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        if (UpsideDownBand.startX(level) == UpsideDownBand.OFF) return false;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.hasData(ModDataAttachments.NEEDS_UPSIDE_DOWN_MIRROR.get());
    }
}
