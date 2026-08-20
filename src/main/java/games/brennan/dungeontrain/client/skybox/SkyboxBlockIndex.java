package games.brennan.dungeontrain.client.skybox;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side index of every {@code skybox_block} near the camera, refreshed on a
 * timer and published as an immutable {@link Snapshot} for
 * {@link SkyboxPunchRenderer} to read once per frame.
 *
 * <h2>Why a scan rather than a BlockEntity</h2>
 * <p>The obvious design — give the block a {@code BlockEntity} and let it add
 * itself to a set on load — is unreliable here. Dungeon Train's worldgen writes
 * blocks straight into chunk-section palettes (skipping lighting and block-entity
 * creation), so structure-placed skybox blocks would silently never register.
 * Scanning the palettes instead sees every block however it got there.</p>
 *
 * <p>The scan is cheap because
 * {@link LevelChunkSection#maybeHas(java.util.function.Predicate)} answers from the
 * section's <em>palette</em> — typically a few dozen reference comparisons — so a
 * whole 16³ section is rejected without touching its 4096 block entries.</p>
 *
 * <h2>Two worlds</h2>
 * <p>Carriage blocks live in Sable sub-level plots, which are ordinary
 * {@link LevelChunk}s in the same {@link ClientLevel} but parked at far-away plot
 * coordinates and drawn through a per-carriage transform. They are therefore
 * indexed separately, keyed by sub-level {@link UUID}, and their positions stay in
 * <em>plot</em> space — the renderer applies the carriage's render pose. The
 * camera-centred main-world sweep explicitly skips plot chunks
 * ({@link SubLevelContainer#inBounds(BlockPos)}) so a player riding a train, whose
 * tick-space position is deep inside a plot, can never leak carriage blocks into
 * the untransformed main-world list.</p>
 *
 * <h2>Freshness</h2>
 * <p>A full rebuild every {@link #REBUILD_INTERVAL_TICKS} ticks, rather than
 * incremental invalidation, keeps the published state immutable and the code
 * small. The cost is a worst case of half a second before a newly placed block
 * starts punching. If that ever reads as lag, the fix is a client mixin on
 * {@code LevelRenderer#blockChanged} to force an immediate rebuild — deliberately
 * not done up front, since these blocks are placed by structures rather than
 * spammed by players.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SkyboxBlockIndex {

    /**
     * Chunk radius around the camera scanned in the main world. 8 chunks (128
     * blocks) comfortably covers any hole a player can read detail in; raising it
     * scales the sweep cost with the square of the radius.
     */
    private static final int SCAN_CHUNK_RADIUS = 8;

    /** Ticks between full rebuilds. 10 = twice a second. */
    private static final int REBUILD_INTERVAL_TICKS = 10;

    /**
     * Extra reach, in blocks, when deciding whether a carriage is close enough to
     * index. Measured from the carriage's world AABB, so it only has to cover the
     * gap between that box and the scan sphere.
     */
    private static final double SUB_LEVEL_SCAN_MARGIN = SCAN_CHUNK_RADIUS * 16.0;

    /** One carriage's skybox blocks, in that carriage's plot-space coordinates. */
    public record SubLevelCubes(UUID subLevelId, long[] positions, byte[] faceMasks) {}

    /**
     * An immutable frame of index state. {@code positions} hold
     * {@link BlockPos#asLong} values; {@code faceMasks} hold one bit per
     * {@link Direction#get3DDataValue()} that is set when that face should be
     * drawn (i.e. the neighbour is not itself a skybox block).
     */
    public record Snapshot(long[] mainPositions, byte[] mainFaceMasks, List<SubLevelCubes> subLevels) {

        public static final Snapshot EMPTY = new Snapshot(new long[0], new byte[0], List.of());

        public boolean isEmpty() {
            return mainPositions.length == 0 && subLevels.isEmpty();
        }
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * Last camera position seen by the renderer, in world space. The sweep centres
     * on this rather than on the player entity, because a player riding a Sable
     * carriage reports far plot-space coordinates during the tick (see the
     * {@code project_sable_tick_vs_render_coords} note) — centring on those would
     * scan the wrong region entirely.
     */
    private static volatile Vec3 cameraWorld = null;

    private static int tickCounter = 0;

    private SkyboxBlockIndex() {}

    /** The current index. Never null; read once per frame by the renderer. */
    public static Snapshot snapshot() {
        return snapshot;
    }

    /** Called from the render thread each frame so the next sweep knows where to look. */
    public static void reportCamera(Vec3 world) {
        cameraWorld = world;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter < REBUILD_INTERVAL_TICKS) return;
        tickCounter = 0;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Vec3 camera = cameraWorld;
        if (level == null || camera == null) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        snapshot = rebuild(level, camera);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        snapshot = Snapshot.EMPTY;
        cameraWorld = null;
        tickCounter = 0;
    }

    private static Snapshot rebuild(ClientLevel level, Vec3 camera) {
        Block skybox = ModBlocks.SKYBOX_BLOCK.get();
        SubLevelContainer container = SubLevelContainer.getContainer(level);

        List<Long> mainHits = new ArrayList<>();
        scanMainWorld(level, camera, skybox, container, mainHits);

        long[] mainPositions = new long[mainHits.size()];
        for (int i = 0; i < mainPositions.length; i++) mainPositions[i] = mainHits.get(i);
        byte[] mainMasks = new byte[mainPositions.length];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < mainPositions.length; i++) {
            mainMasks[i] = faceMask(mainPositions[i], skybox, cursor,
                (pos) -> level.getBlockState(pos));
        }

        List<SubLevelCubes> subs = scanSubLevels(level, camera, skybox);
        return new Snapshot(mainPositions, mainMasks, subs);
    }

    /**
     * Sweep the chunk square around the camera. Plot chunks are excluded here — they
     * are handled by {@link #scanSubLevels}, which pairs each block with the
     * carriage transform it must be drawn through.
     */
    private static void scanMainWorld(ClientLevel level, Vec3 camera, Block skybox,
                                      SubLevelContainer container, List<Long> out) {
        int centreX = SectionPos.blockToSectionCoord((int) Math.floor(camera.x));
        int centreZ = SectionPos.blockToSectionCoord((int) Math.floor(camera.z));

        for (int cx = centreX - SCAN_CHUNK_RADIUS; cx <= centreX + SCAN_CHUNK_RADIUS; cx++) {
            for (int cz = centreZ - SCAN_CHUNK_RADIUS; cz <= centreZ + SCAN_CHUNK_RADIUS; cz++) {
                if (container != null && container.inBounds(new ChunkPos(cx, cz))) continue;
                ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                collectFromChunk(chunk, skybox, out);
            }
        }
    }

    /**
     * Index every carriage whose world AABB is near the camera. Positions stay in
     * plot space; the renderer transforms them through
     * {@link ClientSubLevel#renderPose(float)} at draw time.
     */
    private static List<SubLevelCubes> scanSubLevels(ClientLevel level, Vec3 camera, Block skybox) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();

        AABB near = new AABB(camera, camera).inflate(SUB_LEVEL_SCAN_MARGIN);
        List<SubLevelCubes> out = new ArrayList<>();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            // A sub-level reports an all-zero AABB until its first physics tick — the
            // same defensive check CarriageOcclusion / NearestCarriage make.
            if (box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
                && box.maxX() == 0 && box.maxY() == 0 && box.maxZ() == 0) continue;
            if (!box.intersects(near)) continue;

            LevelPlot plot = sub.getPlot();
            if (plot == null) continue;

            // Plot chunks are NOT reachable through ClientLevel#getBlockState — they
            // live in the plot's own holder. Gather them into a map and read through
            // it, the same way CarriageOcclusion does for its render-time ray casts.
            Map<Long, LevelChunk> chunks = new HashMap<>();
            List<Long> hits = new ArrayList<>();
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;
                chunks.put(chunk.getPos().toLong(), chunk);
                collectFromChunk(chunk, skybox, hits);
            }
            if (hits.isEmpty()) continue;

            long[] positions = new long[hits.size()];
            for (int i = 0; i < positions.length; i++) positions[i] = hits.get(i);
            byte[] masks = new byte[positions.length];
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int i = 0; i < positions.length; i++) {
                masks[i] = faceMask(positions[i], skybox, cursor,
                    pos -> plotBlockState(chunks, pos));
            }
            out.add(new SubLevelCubes(sub.getUniqueId(), positions, masks));
        }
        return List.copyOf(out);
    }

    /** Read a plot-space block through a carriage's own loaded-chunk map. */
    private static BlockState plotBlockState(Map<Long, LevelChunk> chunks, BlockPos pos) {
        LevelChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    /** Palette-test each section, then walk only the sections that can contain a hit. */
    private static void collectFromChunk(ChunkAccess chunk, Block skybox, List<Long> out) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSection();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(state -> state.is(skybox))) continue;

            int baseY = (minSectionY + i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(skybox)) {
                            out.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
    }

    /**
     * Which of the six faces are worth drawing: a face shared with another skybox
     * block is interior and contributes nothing, since the nearer surface already
     * owns those pixels in the depth buffer.
     *
     * <p>Neighbour states are read live at index time. A block added or removed
     * across a section boundary between sweeps can therefore leave a mask stale for
     * up to one rebuild interval — harmless in the "extra interior face" direction
     * (depth-only writes, nearest wins) and self-correcting on the next sweep.</p>
     */
    private static byte faceMask(long packed, Block skybox, BlockPos.MutableBlockPos cursor,
                                 java.util.function.Function<BlockPos, BlockState> lookup) {
        int x = BlockPos.getX(packed);
        int y = BlockPos.getY(packed);
        int z = BlockPos.getZ(packed);
        int mask = 0;
        for (Direction dir : Direction.values()) {
            cursor.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            if (!lookup.apply(cursor).is(skybox)) {
                mask |= 1 << dir.get3DDataValue();
            }
        }
        return (byte) mask;
    }
}
