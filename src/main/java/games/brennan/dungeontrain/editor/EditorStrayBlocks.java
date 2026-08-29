package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the blocks an author has left <b>outside</b> every editor template plot, so the client
 * can paint a red ghost over them.
 *
 * <h2>Why this exists</h2>
 * <p>Only what sits inside a plot's footprint is captured when the template is saved. A block
 * placed against the outside of the bedrock cage — the most natural surface in the whole plot
 * to build against — is silently dropped on save, and nothing in the world says so. Worse, the
 * mistake is invisible from the next plot along, which is where an author usually is by the
 * time they would notice.</p>
 *
 * <h2>What counts as a stray</h2>
 * <p>A cell in the editor region that is not air, is not {@link Blocks#BEDROCK}, and falls
 * outside every plot footprint of the currently stamped category. Bedrock is excluded because
 * it <b>is</b> the cage: every editor ({@link CarriageEditor}, {@link CarriageContentsEditor},
 * {@link CarriagePartEditor}, {@link TrackEditor}, {@link PillarEditor}, {@link TunnelEditor},
 * {@link games.brennan.dungeontrain.editor.PortalRoomEditor}) draws its 12 cage edges with the
 * same {@code OUTLINE_BLOCK}, and nothing else is ever stamped outside a footprint.</p>
 *
 * <p>One more exemption, {@link EditorCategory#PORTALS} only: a door block standing on a room's own
 * doorway column — see {@link #isDoorColumnBlock}. That column is one block outside the room's box
 * by construction, the same surface {@link games.brennan.dungeontrain.portal.PortalRoomDoorDetection}
 * reads a door's position from, so a door built there is the point rather than a mistake.</p>
 *
 * <h2>A sweep, not an event hook</h2>
 * <p>Detection is a budgeted round-robin scan of the region rather than a block-place listener.
 * That costs a small per-tick budget and buys three things a listener cannot have: it catches
 * strays no player placed ({@code /setblock}, {@code /fill}, leftovers from a previous
 * session), it needs no break-side bookkeeping to stay honest, and it is <b>self-healing</b> —
 * a chunk's result is replaced outright each time that chunk comes round, so a removed stray's
 * ghost clears on its own within one cycle rather than depending on an unhook firing.</p>
 *
 * <p>The budget is what keeps it cheap: the sweep only runs while somebody is up at the editor
 * with a category stamped, it walks {@link #CHUNKS_PER_TICK} chunk columns per tick, and it
 * skips whole {@link LevelChunkSection#hasOnlyAir() empty sections} — which the editor sky
 * overwhelmingly is.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorStrayBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunk columns scanned per level tick. A category's region is a few hundred columns, so a full cycle lands in a couple of seconds. */
    private static final int CHUNKS_PER_TICK = 4;

    /**
     * Blocks of headroom above the tallest plot included in the swept region. Generous because a
     * stray is often a column an author walked up; anything above this is missed, which is a
     * cosmetic gap rather than a wrong answer.
     */
    private static final int HEADROOM = 16;

    /** Blocks of margin below the lowest plot floor — the cage sits one block down, nothing else does. */
    private static final int UNDERROOM = 2;

    /**
     * Upper bound on how many strays are tracked at once, so one {@code /fill} accident cannot
     * grow an unbounded packet. Clipping is logged once per cycle that hits it.
     */
    private static final int MAX_STRAYS = 2048;

    /** Players who have turned the ghosts OFF. Default is on. */
    private static final Set<UUID> DISABLED = new HashSet<>();

    /** Strays by chunk column. An entry is replaced wholesale each time its column is rescanned. */
    private static final Map<ChunkPos, List<BlockPos>> BY_CHUNK = new HashMap<>();

    /** Bumped whenever {@link #BY_CHUNK} changes, so the per-player push dedups on a long compare. */
    private static volatile long generation = 0L;

    /** Flattened view of {@link #BY_CHUNK}, rebuilt lazily when {@link #generation} moves past {@link #snapshotGeneration}. */
    private static List<BlockPos> snapshot = Collections.emptyList();
    private static long snapshotGeneration = -1L;

    // --- Current sweep cycle -------------------------------------------------

    private static List<PlotBox> boxes = Collections.emptyList();
    /** True while the stamped category is {@link EditorCategory#PORTALS} — gates the door exemption. */
    private static boolean portalRoomsStamped = false;
    private static List<ChunkPos> queue = Collections.emptyList();
    private static int cursor = 0;
    private static int scanMinY = 0;
    private static int scanMaxY = 0;
    private static boolean clippedThisCycle = false;

    private EditorStrayBlocks() {}

    /**
     * One plot's footprint box. Same containment rule as {@link EditorPlotScope#contains}, in the
     * shape the sweep needs: raw ints, no {@link BlockPos} allocation per cell.
     */
    public record PlotBox(BlockPos origin, Vec3i size) {

        public boolean contains(int x, int y, int z) {
            int dx = x - origin.getX();
            int dy = y - origin.getY();
            int dz = z - origin.getZ();
            return dx >= 0 && dx < size.getX()
                && dy >= 0 && dy < size.getY()
                && dz >= 0 && dz < size.getZ();
        }

        /** True when this box overlaps the 16×16 column {@code (chunkX, chunkZ)}. */
        public boolean overlapsColumn(int chunkX, int chunkZ) {
            int cx0 = chunkX << 4;
            int cz0 = chunkZ << 4;
            return origin.getX() <= cx0 + 15 && origin.getX() + size.getX() - 1 >= cx0
                && origin.getZ() <= cz0 + 15 && origin.getZ() + size.getZ() - 1 >= cz0;
        }
    }

    /** True when {@code (x, y, z)} lies outside every box — the pure half of "is this a stray". */
    public static boolean outsideAll(int x, int y, int z, List<PlotBox> plots) {
        for (int i = 0; i < plots.size(); i++) {
            if (plots.get(i).contains(x, y, z)) return false;
        }
        return true;
    }

    /**
     * True when {@code (x, y, z)} is a door block standing on one of {@code plots}' own doorway
     * columns — {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells}'s two ghosted columns,
     * one outside each end of a portal room's box, spanning its two doorway rows.
     *
     * <p>Only ever true under {@link EditorCategory#PORTALS}: those are the only plots this column
     * geometry means anything for, and every other category's cage really does end at its box.
     * {@link games.brennan.dungeontrain.portal.PortalRoomDoorDetection} reads the very same columns
     * to decide where the door goes — a block this method exempts is a block that detection can see,
     * by construction, since both read the identical geometry off {@code PlotBox}.</p>
     */
    private static boolean isDoorColumnBlock(BlockState state, int x, int y, int z, List<PlotBox> plots) {
        if (!portalRoomsStamped) return false;
        if (!(state.getBlock() instanceof DoorBlock)) return false;
        for (int i = 0; i < plots.size(); i++) {
            PlotBox box = plots.get(i);
            boolean nearColumn = x == box.origin().getX() - 1;
            boolean farColumn = x == box.origin().getX() + box.size().getX();
            if (!nearColumn && !farColumn) continue;
            if (y != box.origin().getY() + 1 && y != box.origin().getY() + 2) continue;
            if (z < box.origin().getZ() + 1 || z > box.origin().getZ() + box.size().getZ() - 2) continue;
            return true;
        }
        return false;
    }

    // --- Per-player toggle ---------------------------------------------------

    /** Toggle the red ghosts for {@code playerId}. {@code on == true} resumes them. */
    public static void setEnabled(UUID playerId, boolean on) {
        if (on) DISABLED.remove(playerId);
        else DISABLED.add(playerId);
    }

    public static boolean isEnabled(UUID playerId) {
        return !DISABLED.contains(playerId);
    }

    // --- Sweep ---------------------------------------------------------------

    /** The generation counter the per-player push dedups against. */
    public static long generation() {
        return generation;
    }

    /** Every stray currently known, in no particular order. Safe to hand straight to a packet. */
    public static synchronized List<BlockPos> snapshot() {
        if (snapshotGeneration == generation) return snapshot;
        List<BlockPos> flat = new ArrayList<>();
        for (List<BlockPos> chunk : BY_CHUNK.values()) {
            flat.addAll(chunk);
        }
        snapshot = Collections.unmodifiableList(flat);
        snapshotGeneration = generation;
        return snapshot;
    }

    /**
     * Forget every stray and abandon the cycle in progress. Called when the plots themselves are
     * torn down ({@link EditorCategory#clearAllPlots}) — with nothing stamped there is nothing to
     * be outside of — and on world quit.
     */
    public static synchronized void clear() {
        boolean had = !BY_CHUNK.isEmpty();
        BY_CHUNK.clear();
        boxes = Collections.emptyList();
        portalRoomsStamped = false;
        queue = Collections.emptyList();
        cursor = 0;
        clippedThisCycle = false;
        if (had) generation++;
    }

    /**
     * Wipe every static above on world quit. The integrated server runs many worlds in one JVM,
     * so without this a player who edited in world A would carry both the stray set and their
     * ghost toggle into world B — same reason {@link VariantOverlayRenderer} clears here.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DISABLED.clear();
        clear();
    }

    /**
     * Advance the sweep by one tick's worth of chunk columns. Caller has already established that
     * this is the overworld, that a category is stamped, and that at least one player is up at the
     * editor — this method does no gating of its own beyond starting a fresh cycle when the
     * previous one has run out.
     */
    public static synchronized void sweepStep(ServerLevel level, CarriageDims dims) {
        if (cursor >= queue.size()) startCycle(level, dims);
        if (queue.isEmpty()) return;

        int budget = Math.min(CHUNKS_PER_TICK, queue.size() - cursor);
        for (int i = 0; i < budget; i++) {
            scanColumn(level, queue.get(cursor++));
        }
    }

    /**
     * Rebuild the plot boxes and the column queue for a new cycle.
     *
     * <p>Rebuilt every cycle rather than cached against an invalidation hook: portal rooms resize,
     * variants are created and deleted, and the boxes are the one thing that must never lag the
     * world — a stale box paints a red ghost over a block that is genuinely inside the plot.
     * A few dozen {@link Template#editorPlotOrigin} calls once every couple of seconds is a small
     * price for not owning that seam.</p>
     */
    private static void startCycle(ServerLevel level, CarriageDims dims) {
        cursor = 0;
        clippedThisCycle = false;
        Optional<EditorCategory> category = EditorStampedCategoryState.current();
        if (category.isEmpty()) {
            boxes = Collections.emptyList();
            portalRoomsStamped = false;
            queue = Collections.emptyList();
            return;
        }
        portalRoomsStamped = category.get() == EditorCategory.PORTALS;

        List<PlotBox> found = new ArrayList<>();
        for (Template model : category.get().models()) {
            BlockPos origin = model.editorPlotOrigin(level, dims);
            if (origin == null) continue;
            Vec3i size = model.plotSize(dims);
            if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) continue;
            found.add(new PlotBox(origin, size));
        }
        boxes = found;
        if (found.isEmpty()) {
            queue = Collections.emptyList();
            return;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PlotBox box : found) {
            minX = Math.min(minX, box.origin().getX());
            minY = Math.min(minY, box.origin().getY());
            minZ = Math.min(minZ, box.origin().getZ());
            maxX = Math.max(maxX, box.origin().getX() + box.size().getX() - 1);
            maxY = Math.max(maxY, box.origin().getY() + box.size().getY() - 1);
            maxZ = Math.max(maxZ, box.origin().getZ() + box.size().getZ() - 1);
        }
        // One GAP of margin on the horizontal axes: that is the whole distance between two plots'
        // cages, so anything an author could wedge beside a plot is inside the swept region.
        minX -= EditorLayout.GAP;
        minZ -= EditorLayout.GAP;
        maxX += EditorLayout.GAP;
        maxZ += EditorLayout.GAP;
        scanMinY = Math.max(level.getMinBuildHeight(), minY - UNDERROOM);
        scanMaxY = Math.min(level.getMaxBuildHeight() - 1, maxY + HEADROOM);

        List<ChunkPos> columns = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                columns.add(new ChunkPos(cx, cz));
            }
        }
        queue = columns;
    }

    /**
     * Rescan one chunk column and replace whatever was recorded for it.
     *
     * <p>Never forces the chunk: an unloaded column has no viewer, and forcing one from a per-tick
     * path is exactly the deadlock the worldgen force guard exists to prevent. Its previous result
     * is left standing rather than cleared — the blocks are still there, they are just out of
     * sight.</p>
     */
    private static void scanColumn(ServerLevel level, ChunkPos column) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(column.x, column.z);
        if (chunk == null) return;

        // The boxes that reach into this column — usually one or two, so the per-cell containment
        // test below stays a couple of comparisons instead of a walk over every plot in the row.
        List<PlotBox> local = new ArrayList<>(2);
        for (PlotBox box : boxes) {
            if (box.overlapsColumn(column.x, column.z)) local.add(box);
        }

        List<BlockPos> found = new ArrayList<>();
        int baseX = column.x << 4;
        int baseZ = column.z << 4;

        for (int y = scanMinY; y <= scanMaxY; ) {
            int sIdx = chunk.getSectionIndex(y);
            if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) {
                y += 16;
                continue;
            }
            int sectionBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            int sectionMaxY = Math.min(scanMaxY, sectionBaseY + 15);
            LevelChunkSection section = chunk.getSection(sIdx);
            // The editor sky is almost entirely empty, so this is the branch that makes the sweep
            // affordable at all.
            if (section.hasOnlyAir()) {
                y = sectionMaxY + 1;
                continue;
            }
            for (int cy = y; cy <= sectionMaxY; cy++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState state = section.getBlockState(lx, cy - sectionBaseY, lz);
                        if (state.isAir()) continue;
                        // Bedrock is the cage, wherever it is.
                        if (state.is(Blocks.BEDROCK)) continue;
                        int wx = baseX + lx;
                        int wz = baseZ + lz;
                        if (!outsideAll(wx, cy, wz, local)) continue;
                        // A door standing on the doorway's own surface is expected, not a mistake —
                        // it is what PortalRoomDoorDetection reads to place the door.
                        if (isDoorColumnBlock(state, wx, cy, wz, local)) continue;
                        found.add(new BlockPos(wx, cy, wz));
                    }
                }
            }
            y = sectionMaxY + 1;
        }

        record(column, found);
    }

    /** Replace {@code column}'s strays, applying the global cap and bumping the generation on any change. */
    private static void record(ChunkPos column, List<BlockPos> found) {
        List<BlockPos> previous = BY_CHUNK.get(column);
        if (found.isEmpty()) {
            if (previous != null) {
                BY_CHUNK.remove(column);
                generation++;
            }
            return;
        }

        int others = 0;
        for (Map.Entry<ChunkPos, List<BlockPos>> entry : BY_CHUNK.entrySet()) {
            if (!entry.getKey().equals(column)) others += entry.getValue().size();
        }
        int room = Math.max(0, MAX_STRAYS - others);
        if (found.size() > room) {
            if (!clippedThisCycle) {
                clippedThisCycle = true;
                LOGGER.warn("[DungeonTrain] Editor stray ghosts: more than {} blocks outside the plots; showing the first {}.",
                    MAX_STRAYS, MAX_STRAYS);
            }
            found = new ArrayList<>(found.subList(0, room));
        }
        if (found.equals(previous)) return;
        if (found.isEmpty()) {
            if (previous != null) {
                BY_CHUNK.remove(column);
                generation++;
            }
            return;
        }
        BY_CHUNK.put(column, found);
        generation++;
    }
}
