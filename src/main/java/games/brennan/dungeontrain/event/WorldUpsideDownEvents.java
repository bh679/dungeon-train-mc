package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.MirrorPlanCache;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.UpsideDownMirror;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Realises the <b>upside-down band</b> ({@link UpsideDownBand}) by mirroring each in-band column of
 * finished overworld terrain vertically around the carriage height — the ground you'd walk on becomes
 * a ceiling overhead, hills that rose above the train hang below it. The reflection algorithm (bedrock
 * roof, water/fallable handling, entry-lead reveal window, exit crossfade, ceiling cap) lives in
 * {@link UpsideDownMirror}; see its javadoc for the full model.
 *
 * <p><b>When + where the work runs (two composed optimisations).</b>
 * <ol>
 *   <li><b>Off-thread precompute (SPAWN).</b> {@code ChunkStatusSpawnMixin} runs the mirror's expensive
 *       half — the full-column snapshot + per-block reflection — on the worldgen worker thread at the
 *       {@code SPAWN} generation step and stashes the resulting {@link UpsideDownMirror.MirrorPlan} in
 *       {@link MirrorPlanCache}. Only the block <em>writes</em> remain for the main thread.</li>
 *   <li><b>Deferred, budgeted apply (main thread).</b> {@link #onChunkLoad} does not mirror inline;
 *       it marks the chunk ({@code NEEDS_UPSIDE_DOWN_MIRROR}) and enqueues it. {@code TrainTickEvents}
 *       drains the queue across ticks under a per-tick nanoTime budget (plus an un-budgeted backstop for
 *       chunks right under the train), calling {@link #applyMirror}. Spreading the writes stops a burst
 *       of freshly-streamed band chunks from blowing one tick's budget.</li>
 * </ol>
 * Together: the precompute makes each {@code applyMirror} cheap (apply a ready plan, ~ms) instead of
 * snapshot+compute+write (~7 ms), so the budgeted drain clears its backlog several times faster. A cache
 * miss (precompute off, plan evicted, or a reload with no SPAWN pass) falls back to computing inline in
 * {@code applyMirror} — identical terrain, just more main-thread work for that chunk.
 *
 * <p>The persistent {@code NEEDS_UPSIDE_DOWN_MIRROR} marker makes the deferral crash/unload-safe;
 * writes go through the raw {@link net.minecraft.world.level.chunk.LevelChunkSection#setBlockState}
 * primitive (the Sable-safe, light-skipping path), guarded by a FULL 3×3 neighbourhood
 * ({@link #neighboursFull}).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldUpsideDownEvents {

    private WorldUpsideDownEvents() {}

    /**
     * At generation, MARK the chunk + enqueue it for the deferred mirror drain instead of mirroring
     * inline. The synchronous ~7 ms/chunk mirror used to run here on the server main thread; when the
     * train streams new band terrain many chunks reach FULL in one tick and the mirror blows the tick
     * budget → stutter. {@code applyMirror} now runs later, spread across ticks under a per-tick budget
     * ({@code TrainTickEvents}), with the persistent {@code NEEDS_UPSIDE_DOWN_MIRROR} marker making the
     * deferral crash/unload-safe. On a plain reload of a chunk that was saved before its mirror applied,
     * re-enqueue it (the marker is still set) — without this the chunk stays permanently un-mirrored
     * because {@code isNewChunk()} is false on reload.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (event.isNewChunk()) {
            if (isInAnyUpsideDownZone(level, chunk)) {
                chunk.setData(ModDataAttachments.NEEDS_UPSIDE_DOWN_MIRROR.get(), Boolean.TRUE);
                data.enqueueMirrorChunk(chunk.getPos().toLong());
            }
        } else if (chunk.hasData(ModDataAttachments.NEEDS_UPSIDE_DOWN_MIRROR.get())) {
            data.enqueueMirrorChunk(chunk.getPos().toLong());
        }

        // Promote any pending chunk whose 3×3 is now complete. Runs on EVERY overworld load — NOT just
        // band chunks — because a band's X-frontier chunk is completed by its non-band neighbour, so
        // gating on band membership would strand it in WAITING forever. Guarded so it costs ~0 when the
        // backlog is empty (the common case, band inactive).
        if (!data.pendingMirrorChunks().isEmpty()) {
            promoteNeighbourhood(level, data, chunk.getPos().x, chunk.getPos().z);
        }
    }

    /**
     * Promote every pending mirror chunk in the 3×3 centred on {@code (cx,cz)} whose full 3×3
     * neighbourhood is now loaded (so its deferred write can pass the {@link #neighboursFull} guard).
     * Called from {@link #onChunkLoad} for every overworld load: the newly-loaded chunk may be the last
     * neighbour any of its 8 surrounding pending chunks (or itself) was waiting on.
     */
    public static void promoteNeighbourhood(ServerLevel level, DungeonTrainWorldData data, int cx, int cz) {
        for (long key : promotableKeys(cx, cz,
                data.pendingMirrorChunks()::contains,
                data.readyMirrorChunks()::contains,
                k -> neighboursFull(level, ChunkPos.getX(k), ChunkPos.getZ(k)))) {
            data.promoteMirrorChunk(key);
        }
    }

    /**
     * Pure readiness decision (no live server) for {@link #promoteNeighbourhood}, unit-testable in the
     * codebase's {@code canBreakAt}/{@code breakFloorY} static-helper style. Returns the chunk keys in the
     * 3×3 centred on {@code (cx,cz)} that should be newly promoted to READY: pending, not-already-ready,
     * and neighbourhood-full per the supplied predicate.
     */
    public static java.util.List<Long> promotableKeys(int cx, int cz,
            java.util.function.LongPredicate isPending,
            java.util.function.LongPredicate isReady,
            java.util.function.LongPredicate neighbourhoodFull) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long key = ChunkPos.asLong(cx + dx, cz + dz);
                if (isPending.test(key) && !isReady.test(key) && neighbourhoodFull.test(key)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /**
     * Drop any pending precomputed plans when the overworld unloads, so a stale plan from a previous
     * world can never be applied to a same-positioned chunk in a newly loaded one.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(Level.OVERWORLD)) {
            MirrorPlanCache.clear();
        }
    }

    /** True iff any column of {@code chunk} lies in the upside-down band, its entry lead-in, or its exit fade. */
    private static boolean isInAnyUpsideDownZone(ServerLevel level, ChunkAccess chunk) {
        long startX = UpsideDownBand.startX(level);
        if (startX == UpsideDownBand.OFF) return false;
        int chunkMinX = chunk.getPos().getMinBlockX();
        if (chunkMinX + 15 < startX) return false;           // entirely before the first band
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            if (UpsideDownBand.isInBand(level, worldX)
                    || UpsideDownBand.isInEntryLead(level, worldX)
                    || UpsideDownBand.isInExitFade(level, worldX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply the upside-down mirror to one chunk on the MAIN THREAD, then lay the flipped corridor.
     * Prefers the {@link UpsideDownMirror.MirrorPlan} precomputed off-thread at SPAWN
     * ({@code ChunkStatusSpawnMixin} → {@link MirrorPlanCache}); on a cache miss it computes the plan
     * inline here — identical terrain, just more work on this thread. Self-gating: returns {@code false}
     * without writing if the chunk is no longer in a band zone (e.g. the band was disabled after the
     * marker was set).
     *
     * <p><b>Not idempotent on its own:</b> {@link UpsideDownMirror#compute} re-snapshots the column, so
     * re-running it on an already-mirrored chunk would mirror the mirror. Exactly-once is the caller's
     * job via the {@code NEEDS_UPSIDE_DOWN_MIRROR} marker check-and-clear (single server thread). Returns
     * whether the chunk was mirrored (band-relevant), so the caller resends it only when needed.</p>
     */
    public static boolean applyMirror(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();

        // Prefer the plan precomputed off-thread at SPAWN; fall back to computing inline on a miss
        // (precompute off, plan evicted, or a reload with no SPAWN pass). Null = not in the band/lead/exit.
        UpsideDownMirror.MirrorPlan plan = MirrorPlanCache.remove(pos.toLong());
        if (plan == null) plan = UpsideDownMirror.compute(level, chunk);
        if (plan == null) return false;

        UpsideDownMirror.apply(chunk, plan);

        // Lay the flipped corridor into the composited terrain — the counterpart to TrackBedFeature
        // (which skips band / lead-in / exit-fade chunks). Runs after the mirror so it wins the corridor
        // Z-strip. Needs the LevelChunk + the world's carriage dims.
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TrackGenerator.layFlippedCorridor(level, chunk, data.dims(), pos.x, pos.z, g);
        chunk.setUnsaved(true);
        return true;
    }

    // ---- deferred-apply plumbing --------------------------------------------------------------

    /** Apply the mirror to a marked, neighbour-FULL chunk, clear its marker, and resend it to trackers. */
    public static void applyMirrorAndResend(ServerLevel level, LevelChunk chunk) {
        boolean changed = applyMirror(level, chunk);
        chunk.removeData(ModDataAttachments.NEEDS_UPSIDE_DOWN_MIRROR.get());  // exactly-once: clear even on a no-op self-heal
        chunk.setUnsaved(true);                                               // persist the cleared marker (applyMirror only sets it when changed)
        if (changed) resendChunk(level, chunk);
    }

    /**
     * True iff the chunk and all 8 neighbours are loaded to FULL. The deferred writes use the raw
     * lock-skipping {@code LevelChunkSection.setBlockState(...,false)} primitive, which can race a worker
     * light task reading the same section lock-free ({@code MissingPaletteEntryException}); a FULL 3×3
     * neighbourhood means no such async light task is still touching these sections — the same guard
     * {@code TrainCarriageAppender.ensureSpawnFootprintReady} uses.
     */
    public static boolean neighboursFull(ServerLevel level, int cx, int cz) {
        var cache = level.getChunkSource();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (cache.getChunkNow(cx + dx, cz + dz) == null) return false;
            }
        }
        return true;
    }

    /** Resend the whole chunk (one {@code ClientboundLevelChunkWithLightPacket} per tracker) after a deferred mirror. */
    public static void resendChunk(ServerLevel level, LevelChunk chunk) {
        var players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        if (players.isEmpty()) return;
        // Same packet PlayerChunkSender.sendChunk builds (that method is private); ships the mirrored
        // blocks + the current gen-light snapshot, replacing the client's copy (idempotent, no double-light).
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }
}
