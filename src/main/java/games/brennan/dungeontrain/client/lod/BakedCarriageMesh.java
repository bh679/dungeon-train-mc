package games.brennan.dungeontrain.client.lod;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * One carriage sub-level's geometry, baked into a {@link VertexBuffer} per {@link RenderType}.
 *
 * <p>This is the whole point of the far tier: Sable draws a NEAR carriage by walking its render
 * sections and issuing a draw per section per layer, with a per-sub-level shader-uniform setup on
 * top. A FAR carriage collapses to <b>one buffer bind and one draw per layer</b> — typically two
 * (solid + cutout), since {@link CarriageMeshBaker} drops translucent geometry.</p>
 *
 * <p>Positions are baked in sub-level local space, so drawing is a matrix push of the carriage's
 * current {@code renderPose} and nothing per-vertex changes as the train moves. That is what makes
 * a baked carriage free to animate: the mesh is static, only the matrix moves.</p>
 *
 * <p>Not thread-safe and GL-bound — create, draw and {@link #close()} on the render thread only.</p>
 */
public final class BakedCarriageMesh implements AutoCloseable {

    private final Map<RenderType, VertexBuffer> buffers;
    private final BlockPos bakeOrigin;
    private final int bakedSkyLightScale;
    private final int quadCount;
    private boolean closed;

    BakedCarriageMesh(
        Map<RenderType, VertexBuffer> buffers, BlockPos bakeOrigin, int bakedSkyLightScale, int quadCount
    ) {
        this.buffers = buffers;
        this.bakeOrigin = bakeOrigin;
        this.bakedSkyLightScale = bakedSkyLightScale;
        this.quadCount = quadCount;
    }

    /**
     * Sable's per-sub-level sky-light scale at bake time.
     *
     * <p>Lighting is baked into the vertices (that is what makes the draw a single buffer with no
     * per-frame light work), which means it cannot follow the sun on its own. Rather than add a
     * uniform vanilla's chunk shader does not have, {@link CarriageMeshCache} compares this against
     * the sub-level's current scale and rebakes when it has drifted — so a far carriage still
     * darkens at dusk and in a tunnel, just in steps rather than continuously. At the LOD
     * threshold the stepping is not perceptible; the alternative, a mesh frozen at noon brightness,
     * very much is.</p>
     */
    public int bakedSkyLightScale() {
        return bakedSkyLightScale;
    }

    /** The layers this mesh has geometry for, in draw order. */
    public Map<RenderType, VertexBuffer> buffers() {
        return buffers;
    }

    /**
     * Plot-space corner the vertices were baked relative to. The renderer re-bases this against the
     * pose's {@code rotationPoint} every frame, so a rotationPoint that moves (mass redistribution,
     * a Sable split) shifts the draw matrix rather than invalidating the mesh.
     */
    public BlockPos bakeOrigin() {
        return bakeOrigin;
    }

    /** Total quads baked — diagnostics only ({@code /dungeontrain debug lod status}). */
    public int quadCount() {
        return quadCount;
    }

    public boolean isEmpty() {
        return buffers.isEmpty();
    }

    /**
     * Release every GL buffer. Idempotent — {@link CarriageMeshCache} can drop a mesh on promotion
     * and again on level unload without tracking which happened first.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (VertexBuffer vb : buffers.values()) {
            if (!vb.isInvalid()) vb.close();
        }
        buffers.clear();
    }

    public boolean isClosed() {
        return closed;
    }
}
