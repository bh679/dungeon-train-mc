package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A template's blocks, tesselated once and left on the GPU.
 *
 * <p>The Open grid spins a tile while you hover it, and a tile is one of up to nine on screen.
 * Re-walking a few thousand block models per frame to do that would be absurd, so the geometry is
 * built once into a {@link VertexBuffer} per chunk render layer and every later frame is a matrix
 * change.</p>
 *
 * <p><b>Why the mesh isn't written to disk.</b> Baked quads carry UVs pointing into the live block
 * texture atlas, and the atlas is rebuilt — with different coordinates — whenever the resource pack
 * set changes. A mesh saved beside the {@code .nbt} would render as garbage the first time someone
 * turned a texture pack on. Session-scoped is the only correct lifetime; {@link BuilderTileMeshCache}
 * owns it and throws the lot away on a resource reload.</p>
 *
 * <p>Light is baked full-bright: a template on disk has no world around it to be lit by, and a
 * thumbnail lit at whatever the builder plot happened to be is a thumbnail you can't read. Shape
 * still reads, because vanilla's per-face shading is baked into the vertex colours here — the same
 * top-bright/side-dimmer/bottom-darkest ramp {@code ModelBlockRenderer} applies in the world. The
 * chunk shaders take their light from the lightmap, not from a directional light, so without this
 * a cube would come out as a flat silhouette.</p>
 *
 * <p>Must be {@link #close() closed} — these are GPU buffers, not garbage.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileMesh implements AutoCloseable {

    /**
     * Past this, don't bake at all.
     *
     * <p>A whole carriage runs to a few thousand blocks and bakes fine. This is the guard against
     * something pathological — a giant imported build — costing a multi-second freeze and a big
     * chunk of VRAM for a picture 100 pixels wide. Over the cap the tile keeps its photo.</p>
     */
    private static final int MAX_BLOCKS = 30_000;

    /** No world to be lit by, so every face is lit as if under open sky at noon. */
    private static final int FULL_BRIGHT = LightTexture.pack(15, 15);

    /** Initial scratch allocation. Grows on demand; only its start size is a guess. */
    private static final int SCRATCH_BYTES = 0x40000;

    private final List<Layer> layers;
    /** Extents of the template in blocks, for the framing maths. */
    private final Vec3i size;
    /** Lowest occupied cell. Normally the origin, but a template need not start at it. */
    private final Vec3i min;

    private record Layer(RenderType type, VertexBuffer buffer) {}

    private BuilderTileMesh(List<Layer> layers, Vec3i size, Vec3i min) {
        this.layers = layers;
        this.size = size;
        this.min = min;
    }

    Vec3i size() {
        return size;
    }

    Vec3i min() {
        return min;
    }

    /**
     * Tesselate {@code cells} into GPU buffers, or null when there is nothing worth drawing.
     *
     * <p>Null covers every "show the photo instead" case: no blocks, over the cap, or a set of
     * blocks that produced no geometry at all (a template of nothing but block entities, say).</p>
     *
     * <p>Expensive — hundreds of model lookups and a GPU upload. Call it from the bake budget, not
     * from every tile that happens to be on screen.</p>
     */
    static BuilderTileMesh bake(Map<BlockPos, BlockState> cells) {
        if (cells.isEmpty() || cells.size() > MAX_BLOCKS) {
            return null;
        }
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        List<Layer> layers = new ArrayList<>();

        for (RenderType type : layersPresent(cells)) {
            VertexBuffer buffer = bakeLayer(cells, blocks, random, type);
            if (buffer != null) {
                layers.add(new Layer(type, buffer));
            }
        }
        if (layers.isEmpty()) {
            return null;
        }
        Vec3i min = cornerOf(cells, true);
        Vec3i max = cornerOf(cells, false);
        Vec3i size = new Vec3i(max.getX() - min.getX() + 1,
                max.getY() - min.getY() + 1,
                max.getZ() - min.getZ() + 1);
        return new BuilderTileMesh(layers, size, min);
    }

    /**
     * Which chunk render layers this template needs, in the order they must be drawn.
     *
     * <p>Collected rather than assumed: baking all four for a template of plain stone would upload
     * three empty buffers. Iterating {@link ChunkRenderTypeSet} keeps the multi-layer blocks
     * (anything whose model spans solid and translucent) honest.</p>
     */
    private static Set<RenderType> layersPresent(Map<BlockPos, BlockState> cells) {
        Set<RenderType> found = new LinkedHashSet<>();
        for (BlockState state : cells.values()) {
            ChunkRenderTypeSet types = ItemBlockRenderTypes.getRenderLayers(state);
            for (RenderType type : types) {
                found.add(type);
            }
        }
        // Draw order matters for the translucent pass; RenderType.chunkBufferLayers() is the
        // canonical order, so filter it rather than trusting insertion order.
        Set<RenderType> ordered = new LinkedHashSet<>();
        for (RenderType type : RenderType.chunkBufferLayers()) {
            if (found.contains(type)) {
                ordered.add(type);
            }
        }
        // Anything a mod introduced that isn't a vanilla chunk layer still gets drawn, last.
        ordered.addAll(found);
        return ordered;
    }

    /** One render layer's worth of geometry, or null when this layer contributed nothing. */
    private static VertexBuffer bakeLayer(Map<BlockPos, BlockState> cells,
                                          BlockRenderDispatcher blocks, RandomSource random,
                                          RenderType type) {
        PoseStack pose = new PoseStack();
        MeshData mesh;
        try (ByteBufferBuilder scratch = new ByteBufferBuilder(SCRATCH_BYTES)) {
            BufferBuilder builder =
                    new BufferBuilder(scratch, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            for (Map.Entry<BlockPos, BlockState> entry : cells.entrySet()) {
                BlockState state = entry.getValue();
                if (!ItemBlockRenderTypes.getRenderLayers(state).contains(type)) {
                    continue;
                }
                BlockPos local = entry.getKey();
                pose.pushPose();
                pose.translate(local.getX(), local.getY(), local.getZ());
                putBlock(pose, builder, blocks, state, local, cells, random, type);
                pose.popPose();
            }
            mesh = builder.build();
            if (mesh == null) {
                return null;
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            return buffer;
        }
    }

    /**
     * One block's quads, from its own model.
     *
     * <p>Same shape as the builder's world-space ghosts ({@code BuilderRoomGhostRenderer.drawGhost}):
     * the unculled quads always go in, because that is where a non-cube keeps all of its geometry,
     * and the per-face quads go in only where the face is actually exposed. That culling is what
     * stops a solid carriage from baking tens of thousands of hidden interior faces.</p>
     */
    private static void putBlock(PoseStack pose, BufferBuilder builder,
                                 BlockRenderDispatcher blocks, BlockState state, BlockPos local,
                                 Map<BlockPos, BlockState> cells, RandomSource random,
                                 RenderType type) {
        BakedModel model = blocks.getBlockModel(state);
        long seed = state.getSeed(local);

        random.setSeed(seed);
        putQuads(pose, builder, model.getQuads(state, null, random, ModelData.EMPTY, type), state);

        for (Direction dir : Direction.values()) {
            if (occludedBy(cells.get(local.relative(dir)))) {
                continue;
            }
            random.setSeed(seed);
            putQuads(pose, builder, model.getQuads(state, dir, random, ModelData.EMPTY, type), state);
        }
    }

    /**
     * Whether a neighbouring cell hides the face pointing at it.
     *
     * <p>Presence alone isn't enough — glass and fences are present and hide nothing, and culling
     * against them would punch holes in the preview. This is vanilla's own occlusion test, asked
     * against an empty world because a template on disk has no world: every block that answers from
     * its own state (which is nearly all of them) answers correctly, and the handful that would
     * want to consult neighbours simply don't cull.</p>
     */
    private static boolean occludedBy(BlockState neighbour) {
        if (neighbour == null) {
            return false;
        }
        try {
            return neighbour.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // A block that insists on a real level rather than crash the screen: draw the face.
            return false;
        }
    }

    /**
     * Vertex colours: the block's tint, times the face's shade.
     *
     * <p>Tint comes from the colour provider so grass and leaves aren't grey. Shade is the ramp
     * {@code ModelBlockRenderer} would have applied — see {@link #shadeOf} for why it has to be
     * done here rather than left to a light.</p>
     */
    private static void putQuads(PoseStack pose, BufferBuilder builder, List<BakedQuad> quads,
                                 BlockState state) {
        for (BakedQuad quad : quads) {
            float shade = shadeOf(quad);
            float r = shade;
            float g = shade;
            float b = shade;
            if (quad.isTinted()) {
                int tint = tintOf(state, quad.getTintIndex());
                r *= (tint >> 16 & 0xFF) / 255.0F;
                g *= (tint >> 8 & 0xFF) / 255.0F;
                b *= (tint & 0xFF) / 255.0F;
            }
            builder.putBulkData(pose.last(), quad, r, g, b, 1.0F, FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
        }
    }

    /**
     * Vanilla's directional face shading, as a plain multiplier.
     *
     * <p>These are {@code Level.getShade}'s constants for a world without constant ambient light.
     * They normally reach the vertex colours through {@code ModelBlockRenderer}, which this path
     * bypasses in favour of {@code BakedModel.getQuads} directly — so a cube would otherwise bake
     * with all six faces the same brightness and read as a flat blob no matter how it is turned.
     * Quads that opt out of shading ({@code !isShade()}, e.g. anything emissive) keep full
     * brightness, exactly as in the world.</p>
     */
    private static float shadeOf(BakedQuad quad) {
        if (!quad.isShade()) {
            return 1.0F;
        }
        return switch (quad.getDirection()) {
            case UP -> 1.0F;
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            case EAST, WEST -> 0.6F;
        };
    }

    /**
     * The tint for a quad, with no level or position to sample.
     *
     * <p>Vanilla's colour providers already handle a null level — that is the path item rendering
     * takes — and answer with the biome-independent default, which is exactly what a template
     * sitting on disk should show.</p>
     */
    private static int tintOf(BlockState state, int tintIndex) {
        try {
            return Minecraft.getInstance().getBlockColors().getColor(state, null, null, tintIndex);
        } catch (RuntimeException e) {
            return 0xFFFFFF;
        }
    }

    /**
     * The lowest or highest occupied cell on each axis.
     *
     * <p>Taken from the blocks rather than from {@code StructureTemplate.getSize()} so the framing
     * is of what you can actually see: a template padded out with air on one side would otherwise
     * hang off-centre in its tile.</p>
     */
    private static Vec3i cornerOf(Map<BlockPos, BlockState> cells, boolean lowest) {
        int x = lowest ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        int y = x;
        int z = x;
        for (BlockPos pos : cells.keySet()) {
            x = lowest ? Math.min(x, pos.getX()) : Math.max(x, pos.getX());
            y = lowest ? Math.min(y, pos.getY()) : Math.max(y, pos.getY());
            z = lowest ? Math.min(z, pos.getZ()) : Math.max(z, pos.getZ());
        }
        return new Vec3i(x, y, z);
    }

    /**
     * Draw every layer with the given transform.
     *
     * <p>Each layer sets up and tears down its own state, the way {@code LevelRenderer} draws chunk
     * layers — the shader and the atlas binding come from the render type, not from us.</p>
     */
    void draw(Matrix4f modelView, Matrix4f projection) {
        for (Layer layer : layers) {
            layer.type().setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                layer.buffer().bind();
                layer.buffer().drawWithShader(modelView, projection, shader);
            }
            VertexBuffer.unbind();
            layer.type().clearRenderState();
        }
    }

    @Override
    public void close() {
        for (Layer layer : layers) {
            layer.buffer().close();
        }
        layers.clear();
    }
}
