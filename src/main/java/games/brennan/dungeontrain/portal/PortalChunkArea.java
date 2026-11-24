package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A chunk dimension room more than one chunk across: a square grid of real, adjacent chunks of one
 * site (3x3 for a 48 x 48 room), sampled together so terrain, trees and structures run across the
 * chunk lines as they do in the world.
 *
 * <p>The one-chunk room is {@link PortalChunkTerrain}'s, and this follows the same rules: nothing is
 * ever loaded from a live level (every chunk is a throwaway {@link ProtoChunk}), the ground is
 * published first so the pair can be planned and crossed, and everything that grows on it follows as
 * a second pass. What differs is the bookkeeping for several chunks:</p>
 * <ul>
 *   <li><b>The grid comes from the template.</b> A room's span is its template width over sixteen
 *       ({@link #span}); the variant names only pick the look.</li>
 *   <li><b>A site is judged by its middle chunk.</b> Only the site that is kept pays for the other
 *       chunks, so turning down a sea or the void costs what it does for a one-chunk room. Every
 *       chunk column of the grid has to pass the stretch test, so a room never straddles the edge of
 *       a band or a modded stretch.</li>
 *   <li><b>Each chunk is generated with the real chunks around it.</b> Its workspace's ring is the
 *       rest of the grid ({@link OfflineChunkSampler#workspaceFor}), so a tree at a chunk's edge
 *       lands in its neighbour instead of being thrown away.</li>
 *   <li><b>Decoration is one task per chunk</b>, each queued behind whatever else is waiting on the
 *       sampler thread: another pair's ground pass never waits out a whole room's decoration.</li>
 * </ul>
 */
public final class PortalChunkArea {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One chunk, in blocks. */
    static final int CHUNK = PortalChunkTerrain.SIZE;

    /** The columns a site is judged on, inset from the middle chunk's corners. */
    private static final int PROBE_INSET = 4;
    /** How far a probe's ground may sit from the middle column's and still agree with it. */
    private static final int PROBE_SPREAD = 8;
    /** Agreeing probes (of five) that take a site at once. */
    private static final int PROBES_REQUIRED = 3;
    /** Candidate sites per roll, as for a one-chunk room. */
    private static final int SITE_ATTEMPTS = 24;
    /** How far in from a mouth the ground under a doorway is measured, for the anchor. */
    private static final int MOUTH_INSET = 1;

    /** Spans by room name, read once from each template. */
    private static final Map<String, Integer> SPANS = new ConcurrentHashMap<>();

    private PortalChunkArea() {}

    // ---- size ----------------------------------------------------------------

    /**
     * How many chunks across a chunk dimension room of {@code size} samples: its width in chunks for
     * a square, chunk-aligned footprint an odd number of chunks across (so there is a middle chunk to
     * judge and plant on), else 1 — the one-chunk room, and any authored room whose footprint does not
     * fit a grid, which keep {@link PortalChunkTerrain}'s own path.
     */
    public static int span(Vec3i size) {
        if (size == null) return 1;
        int x = size.getX();
        int z = size.getZ();
        if (x != z || x % CHUNK != 0) return 1;
        int span = x / CHUNK;
        return span >= 3 && span % 2 == 1 ? span : 1;
    }

    /** {@link #span} of the room called {@code roomName}, read from its template once. */
    public static int spanOf(String roomName) {
        if (roomName == null) return 1;
        return SPANS.computeIfAbsent(roomName, name -> span(PortalRoomTemplateSize.read(name)));
    }

    /** Forget every measured span — templates can be re-saved in the editor. */
    public static void clear() {
        SPANS.clear();
    }

    // ---- sampling ------------------------------------------------------------

    /**
     * One room's grid, mid-flight: every chunk, the workspace each was generated in, and where the
     * room is cut.
     */
    private record Grid(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random,
                        PortalChunkTerrain.Source source, ChunkPos centre, int span,
                        Map<Long, ProtoChunk> chunks, Map<Long, OfflineChunkSampler.Workspace> workspaces,
                        int anchor, int minY, int maxY) {

        int radius() {
            return span / 2;
        }

        int minBlockX() {
            return (centre.x - radius()) * CHUNK;
        }

        int minBlockZ() {
            return (centre.z - radius()) * CHUNK;
        }

        int width() {
            return span * CHUNK;
        }

        ProtoChunk chunkAt(int worldX, int worldZ) {
            return chunks.get(ChunkPos.asLong(worldX >> 4, worldZ >> 4));
        }

        /** The rows of the grid the room will show, in world coordinates. */
        BoundingBox window() {
            int lo = anchor - PortalChunkTerrain.SURFACE_ROW;
            return new BoundingBox(minBlockX(), lo, minBlockZ(),
                minBlockX() + width() - 1, lo + PortalChunkTerrain.HEIGHT - 1, minBlockZ() + width() - 1);
        }

        PortalChunkSlice read() {
            return readSlice(this);
        }
    }

    /**
     * Sample {@code pairKey}'s grid and publish it through {@link PortalChunkTerrain}: the ground now,
     * on this (the sampler) thread, and the decoration as tasks queued behind it.
     */
    static void sample(MinecraftServer server, PortalChunkTerrain.Source source, long seed, int pairKey,
                       int roll, int span, String roomName) {
        long startedAt = System.currentTimeMillis();
        Grid grid = sampleGround(server, source, seed, pairKey, roll, span);
        if (grid == null) {
            PortalChunkTerrain.markFailed(pairKey, roomName);
            LOGGER.warn("[DungeonTrain] Chunk dimension pair {} ('{}', {}, {}x{}) has no sample; the room "
                + "stamps as its plain template", pairKey, roomName, source, span, span);
            return;
        }
        PortalChunkTerrain.publishGround(pairKey, roomName, roll, grid.read());
        long ground = System.currentTimeMillis() - startedAt;
        LOGGER.info("[DungeonTrain] Chunk dimension pair {} ({}x{}) ground sampled from {} at {} in {}, in {} ms",
            pairKey, span, span, source, grid.centre(), biomeAt(grid), ground);
        decorateInTurn(grid, roomName, pairKey, roll, 0, System.currentTimeMillis(), 0L);
    }

    /**
     * Step {@code step} of the decoration: 0 plants the structure, 1..n decorates the grid's chunks
     * one at a time, and the last puts in the mobs and publishes the result. Each step re-queues the
     * next at the back of the sampler's queue.
     */
    private static void decorateInTurn(Grid grid, String roomName, int pairKey, int roll, int step,
                                       long startedAt, long workedMs) {
        // A fixed order (Z rows, then X), so what one chunk's features spill into its neighbour is
        // the same on every run: the grid's own map iterates in no stable order.
        List<ProtoChunk> order = new ArrayList<>(grid.chunks().values());
        order.sort(java.util.Comparator.<ProtoChunk>comparingInt(c -> c.getPos().z).thenComparingInt(c -> c.getPos().x));
        long stepStart = System.currentTimeMillis();
        try {
            if (step == 0) {
                ProtoChunk centre = grid.chunks().get(grid.centre().toLong());
                List<ProtoChunk> others = new ArrayList<>(order);
                others.remove(centre);
                PortalChunkFeatures.plantAcross(grid.generator(), grid.level(), grid.random(), centre, others,
                    grid.window(), grid.level().getSeed(), pairKey, grid.source());
            } else if (step <= order.size()) {
                ProtoChunk chunk = order.get(step - 1);
                PortalChunkFeatures.decorateOne(grid.generator(), grid.workspaces().get(chunk.getPos().toLong()),
                    chunk, grid.source(), pairKey);
            } else {
                PortalChunkFeatures.populateArea(grid.generator(), grid.level(), order,
                    order.stream().map(c -> grid.workspaces().get(c.getPos().toLong())).toList(),
                    grid.workspaces().get(grid.centre().toLong()),
                    grid::chunkAt, grid.minBlockX(), grid.minBlockZ(), grid.width(), grid.window(),
                    grid.level().getSeed(), pairKey);
                PortalChunkSlice decorated = grid.read();
                PortalChunkTerrain.publishDecorated(pairKey, roomName, roll, decorated);
                LOGGER.info("[DungeonTrain] Chunk dimension pair {} ({}x{}) decorated in {} ms of work over {} ms, "
                        + "{} mob(s) generated with it", pairKey, grid.span(), grid.span(),
                    workedMs + System.currentTimeMillis() - stepStart, System.currentTimeMillis() - startedAt,
                    decorated.occupants().size());
                return;
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Chunk dimension pair {} decoration step {} failed", pairKey, step, t);
        }
        long worked = workedMs + System.currentTimeMillis() - stepStart;
        PortalChunkTerrain.submit(() -> decorateInTurn(grid, roomName, pairKey, roll, step + 1, startedAt, worked));
    }

    /** The biome at the middle of the grid's surface row, by id — what says the look landed. */
    private static String biomeAt(Grid grid) {
        try {
            ProtoChunk centre = grid.chunks().get(grid.centre().toLong());
            return centre.getNoiseBiome(QuartPos.fromBlock(grid.centre().getMiddleBlockX()),
                    QuartPos.fromBlock(grid.anchor()), QuartPos.fromBlock(grid.centre().getMiddleBlockZ()))
                .unwrapKey().map(k -> k.location().toString()).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Pick a site, generate its whole grid's ground and carve it. Null when no site had ground. */
    private static Grid sampleGround(MinecraftServer server, PortalChunkTerrain.Source source, long seed,
                                     int pairKey, int roll, int span) {
        PortalChunkSources.Resolved resolved = PortalChunkSources.resolve(server, source, seed);
        if (resolved == null) return null;
        ServerLevel level = resolved.host();
        NoiseBasedChunkGenerator generator = resolved.fallback()
            ? resolved.generator()
            : SampleGenerators.forSource(level, source, resolved.generator());
        if (generator == null) return null;
        RandomState random = resolved.random();
        int minY = resolved.minY();
        int maxY = resolved.maxY();
        int radius = span / 2;

        ProtoChunk bestCentre = null;
        int bestProbes = -1;
        int tried = 0;
        int turnedAway = 0;
        for (PortalChunkTerrain.SitePlan plan = PortalChunkTerrain.SitePlan.of(level, source,
                 generator.getBiomeSource(), random.sampler());
             plan != null && bestProbes < PROBES_REQUIRED; plan = plan.retry()) {
            for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
                ChunkPos site = plan.site(seed, pairKey, roll * SITE_ATTEMPTS + attempt);
                if (!siteAccepted(site, radius, plan::accepts,
                        pos -> !resolved.fallback() && PortalChunkTerrain.voidedByBand(level, pos))) {
                    turnedAway++;
                    continue;
                }
                tried++;
                ProtoChunk centre = groundOf(level, generator, random, site);
                if (centre == null) continue;
                int probes = probes(centre, minY, maxY);
                if (probes > bestProbes) {
                    bestCentre = centre;
                    bestProbes = probes;
                }
                if (bestProbes >= PROBES_REQUIRED) break;
            }
        }
        if (bestCentre == null || bestProbes <= 0) {
            LOGGER.warn("[DungeonTrain] Chunk dimension pair {} ({}, {}x{}) found no ground: {} site(s) "
                    + "generated without anywhere to stand, {} turned away as voided or outside its stretch",
                pairKey, source, span, span, tried, turnedAway);
            return null;
        }
        return grow(level, generator, random, source, bestCentre, span, minY, maxY, pairKey);
    }

    /**
     * Whether a grid centred on {@code centre} may be sampled: every chunk column along X passes the
     * stretch test (the look is keyed on a chunk's X), and no chunk of it is voided by a band.
     */
    static boolean siteAccepted(ChunkPos centre, int radius, java.util.function.Predicate<ChunkPos> accepts,
                                java.util.function.Predicate<ChunkPos> voided) {
        for (int dx = -radius; dx <= radius; dx++) {
            if (!accepts.test(new ChunkPos(centre.x + dx, centre.z))) return false;
            for (int dz = -radius; dz <= radius; dz++) {
                if (voided.test(new ChunkPos(centre.x + dx, centre.z + dz))) return false;
            }
        }
        return true;
    }

    /** One chunk's ground, generated on its own (the judging pass) — null if the generator failed. */
    private static ProtoChunk groundOf(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random,
                                       ChunkPos pos) {
        ProtoChunk chunk = OfflineChunkSampler.blankSample(level, generator, random, pos);
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(level, generator, random, chunk);
        return OfflineChunkSampler.fillGround(level, generator, random, chunk, workspace);
    }

    /** How many of five columns of the middle chunk agree on about one ground height (0 = no ground). */
    private static int probes(ProtoChunk chunk, int minY, int maxY) {
        int anchor = PortalChunkTerrain.standableRow(chunk, CHUNK / 2, CHUNK / 2, minY, maxY);
        if (anchor == PortalChunkTerrain.NO_GROUND) return 0;
        int agreeing = 1;
        int far = CHUNK - 1 - PROBE_INSET;
        int[][] corners = {{PROBE_INSET, PROBE_INSET}, {far, PROBE_INSET}, {PROBE_INSET, far}, {far, far}};
        for (int[] corner : corners) {
            int row = PortalChunkTerrain.standableRow(chunk, corner[0], corner[1], minY, maxY);
            if (row != PortalChunkTerrain.NO_GROUND && Math.abs(row - anchor) <= PROBE_SPREAD) agreeing++;
        }
        return agreeing;
    }

    /**
     * The whole grid around a judged centre: the other chunks' ground, every chunk's workspace ringed
     * by the real grid, the caves, and the row the room is cut around.
     */
    private static Grid grow(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random,
                             PortalChunkTerrain.Source source, ProtoChunk centre, int span, int minY, int maxY,
                             int pairKey) {
        int radius = span / 2;
        ChunkPos mid = centre.getPos();
        Map<Long, ProtoChunk> chunks = new HashMap<>();
        chunks.put(mid.toLong(), centre);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos pos = new ChunkPos(mid.x + dx, mid.z + dz);
                if (chunks.containsKey(pos.toLong())) continue;
                ProtoChunk ground = groundOf(level, generator, random, pos);
                // A neighbour the generator refused stays blank air: still a room, just a hole in it.
                chunks.put(pos.toLong(), ground != null ? ground
                    : OfflineChunkSampler.blankSample(level, generator, random, pos));
            }
        }
        Map<Long, OfflineChunkSampler.Workspace> workspaces = new HashMap<>();
        for (ProtoChunk chunk : chunks.values()) {
            workspaces.put(chunk.getPos().toLong(), OfflineChunkSampler.workspaceFor(level, generator, random, chunk,
                pos -> chunks.get(pos.toLong())));
        }
        for (ProtoChunk chunk : chunks.values()) {
            PortalChunkFeatures.carve(generator, level, random, chunk, workspaces.get(chunk.getPos().toLong()),
                level.getSeed(), pairKey);
        }
        int anchor = anchorRow(chunks, mid, radius, minY, maxY);
        return new Grid(level, generator, random, source, mid, span, Map.copyOf(chunks), Map.copyOf(workspaces),
            anchor, minY, maxY);
    }

    /**
     * The row the room is cut around: the median of the ground under every chunk's middle and under
     * both mouths, so a 48-block room that climbs or falls keeps its doorways inside the window rather
     * than centring on one chunk's ground.
     */
    private static int anchorRow(Map<Long, ProtoChunk> chunks, ChunkPos mid, int radius, int minY, int maxY) {
        List<Integer> rows = new ArrayList<>();
        for (ProtoChunk chunk : chunks.values()) {
            int row = PortalChunkTerrain.standableRow(chunk, CHUNK / 2, CHUNK / 2, minY, maxY);
            if (row != PortalChunkTerrain.NO_GROUND) rows.add(row);
        }
        // The two mouths: the grid's first and last X column, along its middle Z.
        ProtoChunk west = chunks.get(ChunkPos.asLong(mid.x - radius, mid.z));
        ProtoChunk east = chunks.get(ChunkPos.asLong(mid.x + radius, mid.z));
        for (int[] mouth : new int[][] {{0, MOUTH_INSET}, {1, CHUNK - 1 - MOUTH_INSET}}) {
            ProtoChunk chunk = mouth[0] == 0 ? west : east;
            if (chunk == null) continue;
            int row = PortalChunkTerrain.standableRow(chunk, mouth[1], CHUNK / 2, minY, maxY);
            if (row != PortalChunkTerrain.NO_GROUND) rows.add(row);
        }
        if (rows.isEmpty()) return minY + PortalChunkTerrain.SURFACE_ROW;
        return median(rows);
    }

    /** The middle value (the lower of the two middles for an even count). */
    static int median(List<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        return sorted[(sorted.length - 1) / 2];
    }

    // ---- reading ---------------------------------------------------------------

    /** Room-local index of a cell, in the {@link PortalChunkSlice} layout. */
    static int index(int width, int x, int y, int z) {
        return (y * width + z) * width + x;
    }

    /**
     * Where world {@code (x, y, z)} lands in a room of {@code width} cut with its corner at
     * {@code (minX, lo, minZ)}, or null outside it.
     */
    static Integer windowIndex(int width, int minX, int lo, int minZ, int x, int y, int z) {
        int lx = x - minX;
        int ly = y - lo;
        int lz = z - minZ;
        if (lx < 0 || lz < 0 || ly < 0 || lx >= width || lz >= width || ly >= PortalChunkTerrain.HEIGHT) return null;
        return index(width, lx, ly, lz);
    }

    @SuppressWarnings("unchecked")
    private static PortalChunkSlice readSlice(Grid grid) {
        int width = grid.width();
        int height = PortalChunkTerrain.HEIGHT;
        int minX = grid.minBlockX();
        int minZ = grid.minBlockZ();
        int lo = grid.anchor() - PortalChunkTerrain.SURFACE_ROW;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[] states = new BlockState[width * width * height];
        Arrays.fill(states, air);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        List<PortalChunkSlice.Occupant> occupants = new ArrayList<>();
        for (ProtoChunk chunk : grid.chunks().values()) {
            int cx = chunk.getPos().getMinBlockX();
            int cz = chunk.getPos().getMinBlockZ();
            for (int y = 0; y < height; y++) {
                int worldY = lo + y;
                if (worldY < grid.minY() || worldY > grid.maxY()) continue;
                for (int z = 0; z < CHUNK; z++) {
                    for (int x = 0; x < CHUNK; x++) {
                        states[index(width, cx - minX + x, y, cz - minZ + z)] =
                            chunk.getBlockState(cursor.set(cx + x, worldY, cz + z));
                    }
                }
            }
            chunk.getBlockEntityNbts().forEach((at, nbt) -> {
                if (OfflineChunkSampler.isPlaceholderBlockEntity(nbt)) return;
                Integer i = windowIndex(width, minX, lo, minZ, at.getX(), at.getY(), at.getZ());
                if (i != null) blockEntities.put(i, nbt.copy());
            });
            chunk.getBlockEntities().forEach((at, blockEntity) -> {
                Integer i = windowIndex(width, minX, lo, minZ, at.getX(), at.getY(), at.getZ());
                if (i != null) blockEntities.put(i, blockEntity.saveWithFullMetadata(grid.level().registryAccess()));
            });
            for (CompoundTag nbt : chunk.getEntities()) {
                net.minecraft.nbt.ListTag position = nbt.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
                if (position.size() != 3) continue;
                double x = position.getDouble(0) - minX;
                double y = position.getDouble(1) - lo;
                double z = position.getDouble(2) - minZ;
                if (x < 0 || z < 0 || y < 0 || x >= width || z >= width || y >= height) continue;
                CompoundTag copy = nbt.copy();
                copy.remove("UUID");
                occupants.add(new PortalChunkSlice.Occupant(copy, x, y, z));
            }
        }
        return new PortalChunkSlice(grid.source(), width, height, states, blockEntities, occupants,
            biomesIn(grid, width, lo));
    }

    /** The grid's biome at each quart of the window, in {@link PortalChunkSlice#biomeAt}'s layout. */
    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] biomesIn(Grid grid, int width, int lo) {
        int qw = PortalChunkSlice.quarts(width);
        int qh = PortalChunkSlice.quarts(PortalChunkTerrain.HEIGHT);
        Holder<Biome>[] out = new Holder[qw * qh * qw];
        for (int qy = 0; qy < qh; qy++) {
            int worldY = Math.max(grid.minY(), Math.min(grid.maxY(), lo + qy * 4 + 2));
            for (int qz = 0; qz < qw; qz++) {
                for (int qx = 0; qx < qw; qx++) {
                    int x = grid.minBlockX() + qx * 4;
                    int z = grid.minBlockZ() + qz * 4;
                    ProtoChunk chunk = grid.chunkAt(x, z);
                    if (chunk == null) continue;
                    out[(qy * qw + qz) * qw + qx] = chunk.getNoiseBiome(
                        QuartPos.fromBlock(x), QuartPos.fromBlock(worldY), QuartPos.fromBlock(z));
                }
            }
        }
        return out;
    }
}
