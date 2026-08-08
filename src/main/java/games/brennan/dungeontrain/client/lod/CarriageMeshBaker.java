package games.brennan.dungeontrain.client.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ClientLevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bakes a carriage sub-level's blocks into a static {@link BakedCarriageMesh} — the geometry the
 * far LOD tier draws in place of Sable's per-sub-level chunk render.
 *
 * <h2>Where the blocks come from</h2>
 *
 * <p>A sub-level's blocks are ordinary {@link LevelChunk}s living at plot ("shipyard") coordinates
 * in the client level; Sable's render transform is what puts them where you see them. Sable
 * networks a sub-level out to {@code sub_level_tracking_range} (default 320 blocks) regardless of
 * distance, so the client has the blocks for every carriage we might demote — including ones
 * <em>ahead</em> of the player that were never nearby. That is why this needs no new packet and no
 * client-side template access.</p>
 *
 * <h2>Lighting</h2>
 *
 * <p>Geometry is built through a vanilla {@link RenderChunkRegion} obtained from
 * {@link RenderRegionCache}, which is the same route Sable's own section compiler takes — and the
 * route Sable patches for plot lighting. Baking through it means block light (interior torches)
 * and biome tint are baked correctly, rather than being approximated. Sky light is <em>not</em>
 * baked in: it is applied per-draw by {@link CarriageMeshRenderer} from Sable's own per-sub-level
 * sky-light scale, so a far carriage tracks day/night and tunnel darkness and matches how the same
 * carriage was lit a moment earlier at the near tier.</p>
 *
 * <h2>What is deliberately dropped</h2>
 *
 * <ul>
 *   <li><b>Translucent geometry.</b> A baked buffer cannot be re-sorted per camera the way a chunk
 *       section is, so baked glass would show wrong-order artefacts. Dropping it costs a little
 *       fidelity on glass-heavy carriages at distance and avoids a whole class of visual bug.</li>
 *   <li><b>Block entities.</b> Chests, signs and banners are drawn by their own renderers, which
 *       the far tier gates off entirely. At the LOD threshold they are a few pixels.</li>
 *   <li><b>Fluids.</b> Rendered by a separate vanilla path and translucent besides.</li>
 * </ul>
 *
 * <p>Interior faces cost nothing to drop because {@code checkSides = true} already culls any face
 * against a solid neighbour — the same face-culling a chunk mesh gets. This is why "render only the
 * outside layer" is not a separate optimisation: it is what baking already does.</p>
 */
public final class CarriageMeshBaker {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /**
     * Layers baked, in draw order. Translucent is deliberately absent — see the class note. Order
     * matters: cutout after solid so alpha-tested foliage/bars draw over the shell.
     */
    private static final RenderType[] BAKED_LAYERS = {
        RenderType.solid(),
        RenderType.cutoutMipped(),
        RenderType.cutout(),
    };

    private CarriageMeshBaker() {}

    /**
     * Bake {@code sl} into a mesh, or return {@code null} if it has no loaded blocks yet (a
     * freshly-tracked sub-level whose chunks have not arrived). Callers treat {@code null} as
     * "not bakeable yet" and retry — never as an error.
     *
     * <p>Must run on the render thread: it uploads GL buffers.</p>
     */
    @Nullable
    public static BakedCarriageMesh bake(ClientSubLevel sl) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevelPlot plot = sl.getPlot();
        if (plot == null) return null;

        Collection<PlotChunkHolder> holders = plot.getLoadedChunks();
        if (holders.isEmpty()) return null;

        Vector3i min = new Vector3i(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        Vector3i max = new Vector3i(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        for (PlotChunkHolder holder : holders) {
            BoundingBox3ic box = holder.getBoundingBox();
            if (box == null) continue;
            min.set(Math.min(min.x, box.minX()), Math.min(min.y, box.minY()), Math.min(min.z, box.minZ()));
            max.set(Math.max(max.x, box.maxX()), Math.max(max.y, box.maxY()), Math.max(max.z, box.maxZ()));
        }
        if (min.x > max.x) return null; // every holder had an empty bounding box

        // Bake origin: the populated volume's min corner, in plot space. Vertices are stored
        // relative to it, and CarriageMeshRenderer re-bases it against the pose's rotationPoint at
        // draw time — so a rotationPoint that shifts (mass redistribution, a split) never
        // invalidates the mesh.
        BlockPos bakeOrigin = new BlockPos(min.x, min.y, min.z);

        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        Map<RenderType, ByteBufferBuilder> backing = new LinkedHashMap<>();
        for (RenderType layer : BAKED_LAYERS) {
            ByteBufferBuilder bb = new ByteBufferBuilder(layer.bufferSize());
            backing.put(layer, bb);
            builders.put(layer, new BufferBuilder(bb, layer.mode(), layer.format()));
        }

        int quads = 0;
        try {
            quads = tesselate(mc, sl, holders, bakeOrigin, min, max, builders);
        } catch (Throwable t) {
            LOGGER.warn("[lod] bake failed for subLevel={} — staying at near tier: {}", sl, t.toString());
            backing.values().forEach(ByteBufferBuilder::close);
            return null;
        }

        Map<RenderType, VertexBuffer> uploaded = new LinkedHashMap<>();
        try {
            for (RenderType layer : BAKED_LAYERS) {
                MeshData mesh = builders.get(layer).build();
                if (mesh == null) continue; // layer had no geometry
                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vb.bind();
                vb.upload(mesh); // consumes and closes `mesh` — must not be closed again here
                uploaded.put(layer, vb);
            }
        } finally {
            VertexBuffer.unbind();
            backing.values().forEach(ByteBufferBuilder::close);
        }

        if (uploaded.isEmpty()) return null;
        return new BakedCarriageMesh(uploaded, bakeOrigin, sl.getLatestSkyLightScale(), quads);
    }

    /**
     * Walk every populated section of the plot, tesselating each block's model into the buffer for
     * its chunk render type. Returns an approximate quad count (diagnostics only).
     */
    private static int tesselate(
        Minecraft mc,
        ClientSubLevel sl,
        Collection<PlotChunkHolder> holders,
        BlockPos bakeOrigin,
        Vector3i min,
        Vector3i max,
        Map<RenderType, BufferBuilder> builders
    ) {
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        RenderRegionCache regionCache = new RenderRegionCache();
        RandomSource random = RandomSource.create();
        PoseStack poseStack = new PoseStack();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int quads = 0;

        for (PlotChunkHolder holder : holders) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            BoundingBox3ic box = holder.getBoundingBox();
            if (box == null) continue;

            int minSection = SectionPos.blockToSectionCoord(Math.max(min.y, box.minY()));
            int maxSection = SectionPos.blockToSectionCoord(Math.min(max.y, box.maxY()));

            for (int sy = minSection; sy <= maxSection; sy++) {
                SectionPos sectionPos = SectionPos.of(chunk.getPos().x, sy, chunk.getPos().z);
                RenderChunkRegion region = regionCache.createRegion(sl.getLevel(), sectionPos);
                if (region == null) continue;

                int x0 = Math.max(box.minX(), sectionPos.minBlockX());
                int x1 = Math.min(box.maxX(), sectionPos.maxBlockX());
                int y0 = Math.max(box.minY(), sectionPos.minBlockY());
                int y1 = Math.min(box.maxY(), sectionPos.maxBlockY());
                int z0 = Math.max(box.minZ(), sectionPos.minBlockZ());
                int z1 = Math.min(box.maxZ(), sectionPos.maxBlockZ());

                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            cursor.set(x, y, z);
                            BlockState state = region.getBlockState(cursor);
                            if (state.isAir()) continue;
                            if (state.getRenderShape() != RenderShape.MODEL) continue;

                            RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
                            BufferBuilder builder = builders.get(layer);
                            if (builder == null) continue; // translucent (and anything else unbaked)

                            poseStack.pushPose();
                            poseStack.translate(
                                x - bakeOrigin.getX(),
                                y - bakeOrigin.getY(),
                                z - bakeOrigin.getZ());
                            // checkSides = true → faces against solid neighbours are culled, so
                            // interior geometry costs nothing without a separate "shell only" pass.
                            dispatcher.renderBatched(state, cursor, region, poseStack, builder, true, random);
                            poseStack.popPose();
                            quads++;
                        }
                    }
                }
            }
        }
        return quads;
    }
}
