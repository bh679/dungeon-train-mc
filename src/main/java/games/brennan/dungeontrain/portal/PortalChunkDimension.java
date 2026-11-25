package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.chunkframe.ChunkFrame;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFramePlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.util.AsyncPerfLog;
import games.brennan.dungeontrain.util.DtLogging;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Writing a sampled chunk of world generation into a {@link PortalRoomMode#CHUNK_DIMENSION} room —
 * the other half of {@link PortalChunkTerrain}, which is where the terrain comes from.
 *
 * <h2>Over the room's own template, never instead of it</h2>
 * <p>The variant is stamped first, exactly as any other room is: it clears the box and lays a plain
 * floor of ground — what a room keeps if a sample ever fails. The terrain is then poured into the
 * <b>whole</b> box. There is no skybox frame inside it: the sky a chunk dimension stands in is its
 * lock skin, one block outside the box ({@link PortalRoomLock}, set to the variant's skybox block
 * in {@code weights.json}), so the chunk is all sixteen blocks wide.</p>

 * <p>The cube is always in hand by the time anything is stamped: a pair is not planned at all until
 * its terrain has been sampled, because the doorways are stood on that terrain — see
 * {@link PortalChunkDoors} and {@code PortalCarriageBuilder.planStructure}.</p>
 *
 * <h2>The mobs land once</h2>
 * <p>A sample carries the entities it was generated with — a biome's animals, a structure's people —
 * and they are spawned when the room is first decorated, not when it is written. A room is rewritten
 * every time the train drifts far enough to re-stamp it, and mobs are not blocks: writing them again
 * would stack a second herd inside the first.</p>
 *
 * <h2>Two blocks are taken out of the terrain at each mouth, and no more</h2>
 * <p>The doorways are stood on the ground the sample landed ({@link PortalChunkDoors}), so there is
 * nothing to cut away to reach them, and cutting anyway is what made a chunk dimension read as a
 * room with two bites taken out of it. What remains is the door's own column: one block deep on the
 * walkway line, two blocks tall, cleared so a tree or a dune that grew in the doorway is not a wall
 * across it. Nothing is added: no floor is bridged in under a doorway the terrain left open.</p>
 *
 * <h2>Poured over several ticks</h2>
 * <p>The stamp lays the template, the skin and the corridors and leaves the terrain to
 * {@link PortalChunkPour}: a few rows a tick, then the frame and the doorways, which dress what the
 * shell and the rows left. Anyone about to walk in has the rest written first
 * ({@link #finishPourIfReady}), so nobody stands in a part-built room. Every write skips
 * {@link PortalCorridorMask}, or a hillside would pour through the corridors already standing.</p>
 */
public final class PortalChunkDimension {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final org.slf4j.Logger PERF_LOGGER = org.slf4j.LoggerFactory.getLogger(DtLogging.PERF);

    /**
     * How many cells of each doorway are kept clear — the pair above the floor row, which is a
     * player's legs and head and nothing else.
     */
    private static final int DOOR_HEIGHT = 2;

    /** Furthest a grid room's buried doorway is dug inward: half a one-chunk room. */
    static final int MAX_DOORWAY_DIG = PortalChunkTerrain.SIZE / 2;

    /**
     * How far in a buried doorway is dug: half the room — except in a room sampled as a grid of
     * chunks ({@link PortalChunkArea#span}), where a mouth stood a few rows under a hill that keeps
     * climbing was dug into a trench half the room long (24 blocks through a hillside or a frozen
     * lake). Those dig no further than a one-chunk room would. Authored rooms keep their own depth.
     */
    static int doorwayDigDepth(Vec3i size) {
        int half = size.getX() / 2;
        return PortalChunkArea.span(size) > 1 ? Math.min(half, MAX_DOORWAY_DIG) : half;
    }

    /** Pairs whose sampled mobs have already been spawned into their room. */
    private static final java.util.Set<Integer> POPULATED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Rooms part-way through being written, oldest first. Server thread only. */
    private static final java.util.Map<Integer, PortalChunkPour> POURS = new java.util.LinkedHashMap<>();

    /**
     * Terrain rooms stamped while their cube was not in hand (dropped from the cache after the room
     * was first built, then re-stamped by a relocation): they wait for the resample and pour then,
     * rather than standing as their bare template. Server thread only.
     */
    private static final java.util.Set<Integer> WAITING = new java.util.LinkedHashSet<>();

    /**
     * Rooms poured from a cube that has not had its decoration pass yet, so their cube must be kept
     * until it lands and is applied. Server thread only.
     */
    private static final java.util.Set<Integer> UNDECORATED = new java.util.HashSet<>();

    private PortalChunkDimension() {}

    /**
     * The pairs whose rooms are still being built from their cube — pouring, framing, waiting for it,
     * or waiting for its decoration — which {@link PortalChunkTerrain}'s cache must never drop.
     * Forgets pairs whose room is gone, or whose decoration will never come.
     */
    private static java.util.Set<Integer> stillBuilding(IntFunction<PortalStructure> live) {
        WAITING.removeIf(pairKey -> live.apply(pairKey) == null);
        UNDECORATED.removeIf(pairKey -> {
            PortalStructure structure = live.apply(pairKey);
            return structure == null || PortalChunkTerrain.failed(pairKey, structure.roomName());
        });
        java.util.Set<Integer> building = new java.util.HashSet<>(POURS.keySet());
        building.addAll(FRAMINGS.keySet());
        building.addAll(WAITING);
        building.addAll(UNDECORATED);
        building.add(PortalTestSession.PAIR_KEY);
        return building;
    }

    /** Start pouring {@code slice} into {@code pairKey}'s room, keeping its cube until it is decorated. */
    private static void startPour(int pairKey, PortalChunkSlice slice) {
        POURS.put(pairKey, PortalChunkPour.of(slice));
        if (!PortalChunkTerrain.isDecorated(pairKey, slice)) UNDECORATED.add(pairKey);
    }

    /** The decoration pass for {@code pairKey} is done with, applied or not needed. */
    private static void decorationDone(int pairKey) {
        PortalChunkTerrain.decorationApplied(pairKey);
        UNDECORATED.remove(pairKey);
    }

    /**
     * Rooms waiting on a resample: pour the ones whose cube has landed; stand the ones whose sample
     * failed as their template, framed, as a room with no sample always has been.
     */
    private static void drainWaiting(ServerLevel level, CarriageDims dims, IntFunction<PortalStructure> live) {
        if (WAITING.isEmpty()) return;
        for (int pairKey : java.util.List.copyOf(WAITING)) {
            PortalStructure structure = live.apply(pairKey);
            if (structure == null) {
                WAITING.remove(pairKey);
                continue;
            }
            // Before asking again: asking clears the failure and samples once more.
            if (PortalChunkTerrain.failed(pairKey, structure.roomName())) {
                WAITING.remove(pairKey);
                // Outside the stamp's stage scope here, so open it again for the frame's placeholders.
                games.brennan.dungeontrain.train.StagePlacementScope.run(
                    PortalCarriageBuilder.stageIdFor(level, pairKey, dims),
                    java.util.OptionalLong.of(games.brennan.dungeontrain.block.flag.FlagRoll.roomKey(level.getSeed(), pairKey)),
                    () -> frame(level, structure, dims, pairKey));
                continue;
            }
            PortalChunkSlice slice = PortalChunkTerrain.slice(level, pairKey, structure.roomName());
            if (slice == null) continue;
            WAITING.remove(pairKey);
            startPour(pairKey, slice);
            // Somebody may already be standing in the bare room: lay it as fast as a tick allows.
            POURS.put(pairKey, POURS.get(pairKey).rushed());
            AsyncPerfLog.debug(PERF_LOGGER, "[portal.pour] pair={} room={} resampled; pouring", pairKey, structure.roomName());
        }
    }

    /**
     * Rewrite the rooms whose cube has since grown its structure and its features.
     *
     * <p>The second half of the two-pass sample ({@link PortalChunkTerrain}): a room is built from
     * the ground alone so its pair can cross immediately, and this puts the trees, the grass and the
     * structure into it a second or two later. Nothing here moves a doorway or a wall, so a player
     * already inside sees a room growing rather than a room changing.</p>
     */
    public static void applyPendingDecoration(ServerLevel level, CarriageDims dims,
                                              IntFunction<PortalStructure> live) {
        PortalChunkTerrain.retainPins(stillBuilding(live));
        drainWaiting(level, dims, live);
        for (int pairKey : PortalChunkTerrain.decorated()) {
            PortalStructure structure = live.apply(pairKey);
            // Not stamped yet — normal, since a cube is usually decorated before the pair that asked
            // for it has been planned. It stays pending and is written when the room exists.
            if (structure == null) continue;
            PortalChunkSlice slice = PortalChunkTerrain.peek(pairKey);
            // A room laid from a captured blob already stands as it was left — decorated, mined and
            // with whatever lived there — so the pass would only undo that.
            if (slice == null || structure.stampsFromBlob()) {
                decorationDone(pairKey);
                continue;
            }
            PortalChunkPour pour = POURS.get(pairKey);
            if (pour == null) {
                POURS.put(pairKey, PortalChunkPour.of(slice));
            } else if (pour.slice() != slice) {
                POURS.put(pairKey, pour.restartedWith(slice));
            }
        }
        drainPours(level, dims, live);
        drainFramings(level, dims, live);
    }

    /**
     * Start writing the chunk {@code structure}'s pair sampled into its room.
     *
     * <p>Called from {@code stampPairStructure} for a chunk-dimension room, once the shell is down. A
     * missing sample is a no-op rather than an error: the room then stands as its template, which is
     * a room.</p>
     *
     * @return whether a pour started, which is whether it will dress the room in its frame
     */
    public static boolean beginPour(ServerLevel level, PortalStructure structure, int pairKey) {
        WAITING.remove(pairKey);
        PortalChunkSlice slice = PortalChunkTerrain.slice(level, pairKey, structure.roomName());
        if (slice != null) {
            startPour(pairKey, slice);
            return true;
        }
        // A pair is planned only once its cube is in hand, so a miss here is a room re-stamped after
        // its cube was dropped from the cache. slice() has just asked for it again (same roll, same
        // site): wait for it and pour then, which dresses the room in its frame as any pour does.
        if (PortalChunkTerrain.failed(pairKey, structure.roomName())) return false;
        WAITING.add(pairKey);
        AsyncPerfLog.debug(PERF_LOGGER, "[portal.pour] pair={} room={} cube not held; waiting for its resample",
            pairKey, structure.roomName());
        return true;
    }

    /**
     * Write the rest of {@code pairKey}'s room now, unless one of its chunks is not there: that read
     * would block the server until it was, so the pour carries on as it was instead.
     */
    public static void finishPourIfReady(ServerLevel level, CarriageDims dims, int pairKey, PortalStructure structure) {
        if (!POURS.containsKey(pairKey) && !FRAMINGS.containsKey(pairKey)) return;
        if (PortalCarriageBuilder.footprintReady(level, structure, dims)) finishPour(level, dims, pairKey, structure);
    }

    /**
     * Make {@code pairKey}'s room safe for someone arriving at world column {@code (worldX, worldZ)},
     * unless one of its chunks is not there (then the pour carries on as it was, as
     * {@link #finishPourIfReady} does).
     *
     * <p>A one-chunk room, and any authored room, is simply finished: that is a chunk's worth of
     * writing. A room sampled as a grid of chunks ({@link PortalChunkArea}) is nine times that, and
     * writing all of it in the tick somebody crossed was a 950 ms tick for a 3x3 Nether. So only the
     * columns within {@link #ARRIVAL_REACH} of where they land are written now, every row of them;
     * the rest of the room is left to the pour, which is put in a hurry
     * ({@link PortalChunkPour#rushed}) so it lands before they can walk out of what is written.</p>
     */
    public static void finishAround(ServerLevel level, CarriageDims dims, int pairKey, PortalStructure structure,
                                    int worldX, int worldZ) {
        PortalChunkPour pour = POURS.get(pairKey);
        if (pour == null) return;
        if (PortalChunkArea.span(structure.roomSize()) <= 1) {
            finishPourIfReady(level, dims, pairKey, structure);
            return;
        }
        long started = System.nanoTime();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        int[] xs = arrivalSpan(worldX - origin.getX(), structure.roomSize().getX());
        int[] zs = arrivalSpan(worldZ - origin.getZ(), structure.roomSize().getZ());
        int height = structure.roomSize().getY();
        // Only the chunks this patch is in need to be there: the rest of a 48-wide footprint often is
        // not yet when someone arrives, and waiting on it left them in an unwritten room.
        boolean ready = chunksLoaded(level, origin.getX() + xs[0], origin.getX() + xs[1],
            origin.getZ() + zs[0], origin.getZ() + zs[1]);
        if (ready && !pour.done(height)) {
            writeStrips(level, structure, dims, pour.slice(), pour.nextStrip(), pour.totalStrips(height),
                xs[0], xs[1], zs[0], zs[1]);
        }
        POURS.put(pairKey, pour.rushed());
        AsyncPerfLog.debug(PERF_LOGGER, "[portal.pour] pair={} room={} arrival x={}..{} z={}..{} ms={}",
            pairKey, structure.roomName(), xs[0], xs[1], zs[0], zs[1], millis(System.nanoTime() - started));
    }

    /** Whether every chunk over world columns {@code x0..x1} by {@code z0..z1} is loaded, read without loading. */
    private static boolean chunksLoaded(ServerLevel level, int x0, int x1, int z0, int z1) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /** Blocks either side of an arrival that a grid room writes at once — see {@link #finishAround}. */
    static final int ARRIVAL_REACH = 8;

    /**
     * The room-local span, {@code {from, to}} inclusive, within {@link #ARRIVAL_REACH} of {@code local}
     * — which is held inside the room first, since a player crossing stands in the corridor just
     * outside its end face.
     */
    static int[] arrivalSpan(int local, int size) {
        int at = Math.max(0, Math.min(size - 1, local));
        return new int[] {Math.max(0, at - ARRIVAL_REACH), Math.min(size - 1, at + ARRIVAL_REACH)};
    }

    /** Write the rest of {@code pairKey}'s room now, whatever it costs — its frame included. */
    public static void finishPour(ServerLevel level, CarriageDims dims, int pairKey, PortalStructure structure) {
        PortalChunkPour pour = POURS.get(pairKey);
        if (pour == null) {
            stepFraming(level, dims, pairKey, structure, true);
            return;
        }
        long started = System.nanoTime();
        int height = structure.roomSize().getY();
        if (!pour.done(height)) writeStrips(level, structure, dims, pour.slice(), pour.nextStrip(), pour.totalStrips(height));
        completePour(level, dims, pairKey, structure, pour, System.nanoTime() - started, /*flushed*/ true);
        // A wide room's frame is queued by the dress; whatever costs it, it is wanted now too.
        stepFraming(level, dims, pairKey, structure, true);
    }

    /**
     * Whether {@code pairKey}'s room is standing bare, waiting for its cube to be resampled — nothing
     * in it is worth capturing, and a capture would carry the bare room on in place of its terrain.
     */
    public static boolean awaitingResample(int pairKey) {
        return WAITING.contains(pairKey);
    }

    /** Stop writing {@code pairKey}'s room: it is being erased. */
    public static void cancelPour(int pairKey) {
        POURS.remove(pairKey);
        FRAMINGS.remove(pairKey);
        WAITING.remove(pairKey);
        UNDECORATED.remove(pairKey);
    }

    /** One room's next step, the oldest whose chunks are there, so several never share a tick's cost. */
    private static void drainPours(ServerLevel level, CarriageDims dims, IntFunction<PortalStructure> live) {
        if (POURS.isEmpty()) return;
        for (int pairKey : java.util.List.copyOf(POURS.keySet())) {
            PortalStructure structure = live.apply(pairKey);
            if (structure == null) {
                POURS.remove(pairKey);
                continue;
            }
            if (!PortalCarriageBuilder.footprintReady(level, structure, dims)) continue;
            stepPour(level, dims, pairKey, structure, POURS.get(pairKey));
            return;
        }
    }

    private static void stepPour(ServerLevel level, CarriageDims dims, int pairKey, PortalStructure structure,
                                 PortalChunkPour pour) {
        long started = System.nanoTime();
        int height = structure.roomSize().getY();
        // A tick of its own, so the frame's cost does not land on top of the last rows'.
        if (pour.done(height)) {
            completePour(level, dims, pairKey, structure, pour, System.nanoTime() - started, /*flushed*/ false);
            return;
        }
        writeStrips(level, structure, dims, pour.slice(), pour.nextStrip(), pour.stripsEnd(height));
        PortalChunkPour next = pour.advanced(height, System.nanoTime() - started);
        // In a hurry, keep going for as long as this tick's time allows — by time rather than by a
        // multiple of the budget, because what a strip costs varies tenfold (a Nether room's lava and
        // lighting made single ticks of 186 ms at the ordinary budget).
        while (next.rush() && !next.done(height) && System.nanoTime() - started < PortalChunkPour.RUSH_NANOS) {
            writeStrips(level, structure, dims, next.slice(), next.nextStrip(), next.stripsEnd(height));
            next = next.continued(height, System.nanoTime() - started);
        }
        POURS.put(pairKey, next);
    }

    private static void completePour(ServerLevel level, CarriageDims dims, int pairKey, PortalStructure structure,
                                     PortalChunkPour pour, long rowNanos, boolean flushed) {
        POURS.remove(pairKey);
        long started = System.nanoTime();
        // This runs from the level tick, outside the stamp's stage scope — open it again so the
        // frame's stage placeholders resolve as they would have inside the stamp.
        games.brennan.dungeontrain.train.StagePlacementScope.run(
            PortalCarriageBuilder.stageIdFor(level, pairKey, dims),
            java.util.OptionalLong.of(games.brennan.dungeontrain.block.flag.FlagRoll.roomKey(level.getSeed(), pairKey)), () -> dress(level, structure, dims, pour.slice(), pairKey));
        // A sample decorated before its room was stamped needs no second pass: this was it.
        if (PortalChunkTerrain.decorated().contains(pairKey) && PortalChunkTerrain.peek(pairKey) == pour.slice()) {
            spawnOccupants(level, structure, dims, pour.slice(), pairKey);
            decorationDone(pairKey);
        } else if (PortalChunkTerrain.isDecorated(pairKey, pour.slice())) {
            // Poured from a cube decorated and applied before (a re-stamp): nothing more is coming.
            UNDECORATED.remove(pairKey);
        }
        AsyncPerfLog.debug(PERF_LOGGER, "[portal.pour] pair={} room={} ticks={} rowsMs={} maxRowTickMs={} lastStepMs={} dressMs={} flushed={}",
            pairKey, structure.roomName(), pour.ticks(), millis(pour.totalNanos()), millis(pour.maxNanos()),
            millis(rowNanos), millis(System.nanoTime() - started), flushed);
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    /**
     * Dress {@code structure}'s room in a frame — one of the frames whose selection names this room,
     * picked by weight for this pair (see {@link ChunkFramePlacer}). A room no frame dresses, or one
     * that is not a chunk box, is left as its terrain in its lock skin.
     */
    public static void frame(ServerLevel level, PortalStructure structure, CarriageDims dims, int pairKey) {
        frame(level, structure, dims, pairKey, null);
    }

    /**
     * {@link #frame}, then {@code after} once the frame is down (or straight away when there is none).
     *
     * <p>A one-chunk room's frame is written here, in one go, as it always was. A room sampled as a
     * grid of chunks has a frame nine times the size — a 3x3 frame in one tick was 180 to 800 ms —
     * so it is queued instead and written a few layers a tick ({@link #drainFramings}), and
     * {@code after} runs when the last layer is down.</p>
     */
    private static void frame(ServerLevel level, PortalStructure structure, CarriageDims dims, int pairKey,
                              Runnable after) {
        // A room that is not a generated box (an authored chunk-dimension room of its own size) has no
        // frame that fits it; frameFor then picks among the frames sized for this room.
        java.util.Optional<ChunkFramePlacer.Picked> frame = java.util.Optional.empty();
        int rollIndex = 0;
        if (ChunkFrame.validSize(ChunkFrame.sizeFor(structure.roomSize()))) {
            // The frame's own roll, salted apart from the room's so a test can re-roll either alone;
            // unsalted it is a pure function of the pair, as in play.
            rollIndex = ChunkFramePlacer.rollIndex(structure.roomName(), pairKey);
            frame = ChunkFramePlacer.frameFor(level, structure.roomName(), pairKey, rollIndex,
                // A pair planned before a restart has no planned frame left: its column's own context.
                games.brennan.dungeontrain.template.GateContext.forCarriage(level, pairKey, dims.length()));
        }
        if (frame.isEmpty()) {
            if (after != null) after.run();
            return;
        }
        if (PortalChunkArea.span(structure.roomSize()) > 1) {
            FRAMINGS.put(pairKey, new Framing(frame.get(), rollIndex, 0, after));
            return;
        }
        placeFrame(level, structure, dims, pairKey, frame.get(), rollIndex, 0, Integer.MAX_VALUE);
        if (after != null) after.run();
    }

    private static int placeFrame(ServerLevel level, PortalStructure structure, CarriageDims dims, int pairKey,
                                  ChunkFramePlacer.Picked frame, int rollIndex, int yFrom, int yTo) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        // Without the seal planes: the frame may dress the mouth's plane, and only the corridor and
        // its plug are kept — which is what cuts the doorway through it.
        // Under a frame's Void or World Border there is no skin, so the frame's shell is left out too.
        return ChunkFramePlacer.place(level, frame, structure.roomOrigin(dims, layout),
            PortalCarriageBuilder.corridorMask(structure, dims, /*withSeals*/ false), rollIndex, yFrom, yTo,
            /*skipShell*/ !structure.mode().sealsRoomBox(),
            seaTopY(structure, dims, pairKey, structure.roomOrigin(dims, layout)));
    }

    /**
     * The world row of an underwater room's sea surface — the highest row of its sample holding water,
     * brought into the room as its terrain was ({@link #copyShift}) — or
     * {@link games.brennan.dungeontrain.portal.chunkframe.FrameWaterlog#NO_SEA} for any other room.
     */
    private static int seaTopY(PortalStructure structure, CarriageDims dims, int pairKey, BlockPos origin) {
        PortalChunkSlice slice = PortalChunkTerrain.peek(pairKey);
        // A room laid from a captured blob is framed with no cube of its own in hand, and the cache
        // may have let its cube go by then: the row remembered from its first pour stands in.
        int top = slice != null ? rememberTopWaterRow(pairKey, slice) : TOP_WATER_ROWS.getOrDefault(pairKey, -1);
        if (top < 0) return games.brennan.dungeontrain.portal.chunkframe.FrameWaterlog.NO_SEA;
        return origin.getY() + top - copyShift(structure, dims, structure.roomSize());
    }

    /**
     * Each underwater pair's highest water row, kept past its cube so a room re-laid from a blob is
     * still framed waterlogged after the cube has left the cache. A few ints a pair. Server thread only.
     */
    private static final java.util.Map<Integer, Integer> TOP_WATER_ROWS = new java.util.HashMap<>();

    /** {@link #topWaterRow} of {@code slice}, or -1 for a room that is not underwater; remembered for the pair. */
    private static int rememberTopWaterRow(int pairKey, PortalChunkSlice slice) {
        if (!slice.source().underwater()) return -1;
        int top = topWaterRow(slice);
        TOP_WATER_ROWS.put(pairKey, top);
        return top;
    }

    /** The highest row of {@code slice} with water in it, or -1 when it holds none. */
    static int topWaterRow(PortalChunkSlice slice) {
        for (int y = slice.height() - 1; y >= 0; y--) {
            for (int z = 0; z < slice.width(); z++) {
                for (int x = 0; x < slice.width(); x++) {
                    BlockState state = slice.at(x, y, z);
                    if (state != null && state.getFluidState().getType()
                            .isSame(net.minecraft.world.level.material.Fluids.WATER)) {
                        return y;
                    }
                }
            }
        }
        return -1;
    }

    /** A wide frame part way through being written: the next layer, and what to do once it is down. */
    private record Framing(ChunkFramePlacer.Picked frame, int rollIndex, int nextY, Runnable after) {}

    /** Wide frames still being written, oldest first. Server thread only. */
    private static final java.util.Map<Integer, Framing> FRAMINGS = new java.util.LinkedHashMap<>();

    /** How long a tick spends on one wide frame's layers: well inside a 50 ms tick. */
    private static final long FRAMING_NANOS = 20_000_000L;

    /** One wide frame's next layers, the oldest whose chunks are there. */
    private static void drainFramings(ServerLevel level, CarriageDims dims, IntFunction<PortalStructure> live) {
        if (FRAMINGS.isEmpty()) return;
        for (int pairKey : java.util.List.copyOf(FRAMINGS.keySet())) {
            PortalStructure structure = live.apply(pairKey);
            if (structure == null) {
                FRAMINGS.remove(pairKey);
                continue;
            }
            if (!PortalCarriageBuilder.footprintReady(level, structure, dims)) continue;
            stepFraming(level, dims, pairKey, structure, false);
            return;
        }
    }

    /** Write {@code pairKey}'s frame for this tick's time — or all of it, when {@code all}. */
    private static void stepFraming(ServerLevel level, CarriageDims dims, int pairKey, PortalStructure structure,
                                    boolean all) {
        Framing framing = FRAMINGS.get(pairKey);
        if (framing == null) return;
        long started = System.nanoTime();
        int height = framing.frame().template().size().getY();
        int[] y = {framing.nextY()};
        games.brennan.dungeontrain.train.StagePlacementScope.run(
            PortalCarriageBuilder.stageIdFor(level, pairKey, dims),
            java.util.OptionalLong.of(games.brennan.dungeontrain.block.flag.FlagRoll.roomKey(level.getSeed(), pairKey)), () -> {
                do {
                    placeFrame(level, structure, dims, pairKey, framing.frame(), framing.rollIndex(), y[0], y[0] + 1);
                    y[0]++;
                } while (y[0] < height && (all || System.nanoTime() - started < FRAMING_NANOS));
                if (y[0] >= height && framing.after() != null) framing.after().run();
            });
        if (y[0] >= height) {
            FRAMINGS.remove(pairKey);
        } else {
            FRAMINGS.put(pairKey, new Framing(framing.frame(), framing.rollIndex(), y[0], framing.after()));
        }
        AsyncPerfLog.debug(PERF_LOGGER, "[portal.frame] pair={} room={} layers={}..{} ms={}{}",
            pairKey, structure.roomName(), framing.nextY(), y[0], millis(System.nanoTime() - started),
            y[0] >= height ? " done" : "");
    }

    // ---- writing -------------------------------------------------------------

    /**
     * Write strips {@code fromStrip} up to {@code toStrip} of the room's box from {@code slice} — a
     * strip being one row at one Z, {@code slice.width()} cells along X ({@link PortalChunkPour}).
     */
    private static void writeStrips(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                    PortalChunkSlice slice, int fromStrip, int toStrip) {
        writeStrips(level, structure, dims, slice, fromStrip, toStrip, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    }

    /** {@link #writeStrips} held to room-local columns {@code xFrom..xTo} by {@code zFrom..zTo}, inclusive. */
    private static void writeStrips(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                    PortalChunkSlice slice, int fromStrip, int toStrip,
                                    int xFrom, int xTo, int zFrom, int zTo) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        PortalCorridorMask mask = PortalCarriageBuilder.corridorMask(structure, dims);

        // Where the copy sits vertically is read off the DOOR, not off the box's own corner. The
        // two are the same number in an ordinary room — the doorways were fitted to this column's
        // ground before the structure was planned, so its ground row already is the door row — and
        // they come apart exactly when a room cannot spend the offset the fit asked for: a world too
        // shallow to stand a 32-tall box up holds it down (PortalCarriageBuilder#heldInRegion) and
        // the offset clamps with it. Aligning on the corner there leaves the doorway hanging in the
        // air above its own ground, or buried under it. Aligning on the door keeps a player's feet
        // on the terrain and spends the shortfall at the top of the column, which is sky.
        int shift = copyShift(structure, dims, size);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The whole box, faces included: the room has no shell of its own — its skybox is the lock
        // skin one block outside it — so the chunk runs all 16 blocks to the sky. Where the end
        // column is open, the mouth's seal ring falls back to that same skin rather than to the
        // floor row (PortalCarriageBuilder#sealFillFor), so an open face still seals with sky.
        int stripWidth = slice.width();
        for (int strip = fromStrip; strip < toStrip; strip++) {
            int y = strip / stripWidth;
            int z = strip % stripWidth;
            if (y >= size.getY() || z >= size.getZ() || z < zFrom || z > zTo) continue;
            for (int x = xFrom; x <= xTo && x < size.getX() && x < slice.width(); x++) {
                // Null for a row the cube does not reach. Those rows keep whatever the template
                // put there, which is a room rather than a hole.
                BlockState state = slice.at(x, y + shift, z);
                if (state == null) continue;
                cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                if (mask.covers(cursor)) continue;
                // Cheap, and it is what makes the decoration pass affordable: the second write
                // of a room differs from the first only where something grew, so all but a
                // handful of these cells are already the block being written.
                if (level.getBlockState(cursor) == state) continue;
                replaceQuietly(level, cursor, state);
                applyBlockEntity(level, cursor, slice.blockEntityAt(x, y + shift, z));
            }
        }
    }

    /** What goes over the terrain once it is all down: the frame, the doorways, the biomes. */
    private static void dress(ServerLevel level, PortalStructure structure, CarriageDims dims,
                              PortalChunkSlice slice, int pairKey) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        PortalCorridorMask mask = PortalCarriageBuilder.corridorMask(structure, dims);
        int shift = copyShift(structure, dims, size);

        Runnable doorways = () -> {
            openDoorway(level, structure, dims, layout, origin, size, mask, PortalCarriageRole.ENTRY);
            openDoorway(level, structure, dims, layout, origin, size, mask, PortalCarriageRole.EXIT);
        };
        Runnable afterFrame = () -> {
            // After the frame: its inner layer is written over the terrain's edge row, and the
            // doorways are cut through it.
            doorways.run();
            if (slice.source().levelKey().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                paintBiomes(level, origin, size, shift, slice);
            }
        };
        // A wide room's frame lands over the next ticks; its doorways are open meanwhile, so someone
        // walking in is not met by terrain in the mouth, and cut again once the frame is down.
        if (PortalChunkArea.span(size) > 1) doorways.run();
        frame(level, structure, dims, pairKey, afterFrame);
    }

    /**
     * Give the room the sampled chunk's biomes, so it is tinted as the place it was cut from.
     *
     * <p>Grass, leaves and water take their colour from the biome of the cell they stand in, and the
     * cells a room is stamped into belong to the world it is stamped into — an editor world's plains,
     * or whatever the live world has down there. So a BoP redwood forest came out plains green. The
     * room's interior is painted with the sample's own biomes, the way {@code /fillbiome} does it,
     * and the chunks are resent so a player already standing there sees it change.</p>
     *
     * <p>Overworld rooms only. A Nether or End room is lit and fogged by the sky its variant
     * authors, and a Nether biome in a sealed dark room would also bring the Nether's spawns with it.
     * A room shares a quart (4×4×4) with whatever is beside its walls, so a biome edge can reach a
     * block past them — the price of painting at the grain biomes are stored at.</p>
     */
    private static void paintBiomes(ServerLevel level, BlockPos origin, Vec3i size, int shift,
                                    PortalChunkSlice slice) {
        // The whole box: the terrain runs to its faces now, so the outermost grass is sampled grass
        // too and wants the sample's tint.
        int minX = origin.getX();
        int minY = origin.getY();
        int minZ = origin.getZ();
        int maxX = origin.getX() + size.getX() - 1;
        int maxY = origin.getY() + size.getY() - 1;
        int maxZ = origin.getZ() + size.getZ() - 1;
        java.util.List<net.minecraft.world.level.chunk.ChunkAccess> changed = new java.util.ArrayList<>();
        net.minecraft.world.level.biome.Climate.Sampler sampler =
            level.getChunkSource().randomState().sampler();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                boolean[] touched = {false};
                chunk.fillBiomesFromNoise((qx, qy, qz, s) -> {
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> here =
                        chunk.getNoiseBiome(qx, qy, qz);
                    int bx = net.minecraft.core.QuartPos.toBlock(qx);
                    int by = net.minecraft.core.QuartPos.toBlock(qy);
                    int bz = net.minecraft.core.QuartPos.toBlock(qz);
                    // A quart counts when its middle is inside the room's box.
                    int mx = bx + 2, my = by + 2, mz = bz + 2;
                    if (mx < minX || mx > maxX || my < minY || my > maxY || mz < minZ || mz > maxZ) {
                        return here;
                    }
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> sampled =
                        slice.biomeAt(mx - origin.getX(), my - origin.getY() + shift, mz - origin.getZ());
                    if (sampled == null || sampled.equals(here)) return here;
                    touched[0] = true;
                    return sampled;
                }, sampler);
                if (touched[0]) {
                    chunk.setUnsaved(true);
                    changed.add(chunk);
                }
            }
        }
        if (!changed.isEmpty()) level.getChunkSource().chunkMap.resendBiomesForChunks(changed);
    }

    /**
     * Forget which pairs have had their mobs spawned.
     *
     * <p>Pair-keyed like everything else here, so the next world opened must not inherit it: pair 12
     * there is a different room, and one that would silently never get its sheep.</p>
     */
    public static void clear() {
        POPULATED.clear();
        POURS.clear();
        WAITING.clear();
        UNDECORATED.clear();
        TOP_WATER_ROWS.clear();
    }

    /**
     * Spawn the mobs the sample was generated with, once per pair.
     *
     * <p>Once, and tracked rather than inferred: a room is rewritten whenever the train drifts far
     * enough to re-stamp its structure, and spawning on every write would leave a herd of sheep
     * standing inside the last herd of sheep. They are ordinary entities from the moment they land —
     * they wander, they can be killed, and a re-stamp leaves them where they are.</p>
     */
    private static void spawnOccupants(ServerLevel level, PortalStructure structure,
                                       CarriageDims dims, PortalChunkSlice slice, int pairKey) {
        if (slice.occupants().isEmpty() || !POPULATED.add(pairKey)) return;
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos origin = structure.roomOrigin(dims, layout);
        Vec3i size = structure.roomSize();
        int shift = copyShift(structure, dims, size);

        int spawned = 0;
        for (PortalChunkSlice.Occupant occupant : slice.occupants()) {
            double x = origin.getX() + occupant.x();
            double y = origin.getY() + occupant.y() - shift;
            double z = origin.getZ() + occupant.z();
            // Outside the room's own box once the copy has been aligned on the door: the column is
            // taller than a shallow world can stand up, and a sheep in the rows that were cut is a
            // sheep in the bedrock.
            if (y < origin.getY() + 1 || y > origin.getY() + size.getY() - 2) continue;
            // Copy: the slice's occupant tag is shared. A sampled villager rolls its own pigman chance.
            CompoundTag occupantNbt = occupant.nbt().copy();
            games.brennan.dungeontrain.compat.PigmanVillagersBridge.freshRoll(occupantNbt);
            Entity entity = EntityType.loadEntityRecursive(
                games.brennan.dungeontrain.editor.FrozenMobs.prepareForSpawn(
                    occupantNbt, level, BlockPos.containing(x, y, z)),
                level, spawning -> {
                spawning.moveTo(x, y, z, spawning.getYRot(), spawning.getXRot());
                return spawning;
            });
            if (entity == null) continue;
            if (level.addFreshEntity(entity)) spawned++;
        }
        if (spawned > 0) {
            LOGGER.info("[DungeonTrain] Chunk dimension pair {} spawned {} of its sample's {} mobs",
                pairKey, spawned, slice.occupants().size());
        }
    }

    /**
     * How far the sampled column slides against the room's own box, read off the door row.
     *
     * <p>Zero in an ordinary room: the doorways were fitted to this column's ground before the pair
     * was planned, so the ground row already <i>is</i> the door row. They come apart only when a room
     * cannot spend the offset the fit asked for — a world too shallow to stand a 32-tall box up holds
     * it down and the offset clamps with it — and then the shortfall is spent at the top of the
     * column, which is sky, rather than under a player's feet.</p>
     */
    private static int copyShift(PortalStructure structure, CarriageDims dims, Vec3i size) {
        int askedFor = structure.settings().doorHeightOffset().value();
        return askedFor - PortalRoomLayout.clampDoorHeightOffset(dims, size.getY(), askedFor);
    }

    /**
     * Open one doorway through the terrain: the two cells of the door itself, and nothing else.
     *
     * <p><b>Two blocks, and not one more.</b> The doorways are stood on the ground the sample landed
     * ({@link PortalChunkDoors}), so nothing has to be cut away to reach them — but a doorway is a
     * hole a player walks through, and a sample is free to have grown a tree trunk or piled a dune
     * in exactly that hole. What is cleared is the door's own column: one block deep at the room's
     * end face, on the walkway line, from the floor row up through the two cells
     * {@code PortalRoomDoorCells} calls a door. Everything either side of it, and everything behind
     * it, is the terrain as it was sampled.</p>
     *
     * <p><b>Nothing is added, either.</b> The floor row under those two cells used to be filled in
     * when the sample left air there, which put a stone block in the mouth of a doorway that opens
     * onto a slope or a stream — a bridge into the room that the terrain never had. The doorways are
     * fitted to the ground, so where that row is not ground it is because the sample says so, and
     * the room is left saying it.</p>
     */
    private static void openDoorway(ServerLevel level, PortalStructure structure, CarriageDims dims,
                                    PortalCarriageLayout layout, BlockPos origin, Vec3i size,
                                    PortalCorridorMask mask, PortalCarriageRole role) {
        boolean entry = role == PortalCarriageRole.ENTRY;
        BlockPos corridor = entry ? structure.origin() : structure.exitOrigin(dims);

        // The room's end face on this side — the column a player steps into off the door plane.
        int x = entry ? origin.getX() : origin.getX() + size.getX() - 1;
        // The walkway line of THIS corridor, read off the corridor itself rather than off the room:
        // a pair's two doorways may sit on different lines, and the corridor is where each one is.
        int z = corridor.getZ() + layout.doorZ();
        // The corridor's origin row is its floor, and the door is the two cells above it — the same
        // pair PortalRoomDoorCells cuts for every other room's doorway.
        int floorY = corridor.getY();

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Then on inward, for as long as the walkway is still rock: a doorway whose mouth the sample
        // buried — a Nether cave whose floor is further in than the end face — is dug through to the
        // open cave rather than left as a door onto a wall. Where the terrain is already open, which
        // is every hillside and most caves, the next column is clear and this stops at the door.
        int step = entry ? 1 : -1;
        int deepest = doorwayDigDepth(size);
        for (int depth = 0; depth <= deepest; depth++) {
            int cx = x + depth * step;
            if (depth > 0 && walkwayOpen(level, cursor, cx, floorY, z)) break;
            for (int dy = 1; dy <= DOOR_HEIGHT; dy++) {
                cursor.set(cx, floorY + dy, z);
                if (mask.covers(cursor)) continue;
                if (!level.getBlockState(cursor).isAir()) replaceQuietly(level, cursor, air);
            }
        }
    }

    /**
     * Overwrite one cell of the room without spilling what was in it.
     *
     * <p>A sampled structure's chest carries its loot table unrolled, and replacing a container rolls
     * it and drops the lot on the floor: a ruined portal's chest standing in a doorway came out as a
     * pile of gold nuggets and flint and steel in front of the door. Taking the block entity away
     * first leaves nothing to roll. The room is being rewritten from the sample, so whatever the
     * chest held belongs to the chunk being replaced, not to the player.</p>
     */
    private static void replaceQuietly(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos).hasBlockEntity()) level.removeBlockEntity(pos);
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** Whether a player could already stand in the walkway at {@code cx}: its door cells are open. */
    private static boolean walkwayOpen(ServerLevel level, BlockPos.MutableBlockPos cursor, int cx,
                                       int floorY, int z) {
        for (int dy = 1; dy <= DOOR_HEIGHT; dy++) {
            if (level.getBlockState(cursor.set(cx, floorY + dy, z)).blocksMotion()) return false;
        }
        return true;
    }

    /**
     * Give a freshly written block its sampled block entity, when it had one.
     *
     * <p>What makes a chest in a chunk dimension a chest rather than a box: the NBT carries
     * {@code LootTable} and {@code LootTableSeed}, so the container fills from the same vanilla table
     * it would have in the world the sample came from, rolled when a player first opens it.</p>
     */
    private static void applyBlockEntity(ServerLevel level, BlockPos pos, CompoundTag nbt) {
        if (nbt == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return;
        blockEntity.loadWithComponents(nbt, level.registryAccess());
        blockEntity.setChanged();
    }
}
