package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * The render state the Train Builder's ghosted track draws with.
 *
 * <p>Extends {@link RenderStateShard} for the same reason
 * {@link games.brennan.dungeontrain.client.menu.MenuRenderStates} does: the shard constants are
 * protected statics, and a subclass can read them from any package. That class builds flat coloured
 * panels; this one builds textured block models, which need a different half of the same toolbox —
 * hence a sibling rather than another method on it.</p>
 */
public final class BuilderRenderStates extends RenderStateShard {

    private static final ShaderStateShard SHADER = RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER;
    private static final TransparencyStateShard TRANSLUCENT = TRANSLUCENT_TRANSPARENCY;
    private static final CullStateShard CULL_ENABLED = CULL;
    private static final DepthTestStateShard DEPTH_LEQUAL = LEQUAL_DEPTH_TEST;
    private static final WriteMaskStateShard WRITE_COLOUR_AND_DEPTH = COLOR_DEPTH_WRITE;
    private static final LightmapStateShard LIGHTMAP_ON = LIGHTMAP;
    private static final OverlayStateShard OVERLAY_ON = OVERLAY;

    /**
     * Translucent block models that never stack on top of one another.
     *
     * <p>Three states carry the whole effect, and each answers one thing a ghosted mass of blocks
     * would otherwise get wrong:</p>
     *
     * <ul>
     *   <li><b>{@code CULL}</b> — only faces turned towards the camera are drawn. Without it you see
     *       the inside of the far side of every block through its own front, which reads as a smear
     *       rather than as a surface.</li>
     *   <li><b>{@code COLOR_DEPTH_WRITE}</b> — a ghost fragment writes depth, so the next ghost
     *       behind it fails the depth test and is dropped. This is what stops a run of forty blocks
     *       compounding into an opaque wall: you see the near surface at half alpha and nothing
     *       behind it. It depends on drawing front-to-back, which
     *       {@link BuilderTrackGhostRenderer} sorts for.</li>
     *   <li><b>{@code LEQUAL} depth test</b> — the opaque world was drawn before this pass and its
     *       depth is already in the buffer, so solid geometry behind a ghost still shows through the
     *       blend. The grass under the track stays exactly where it was.</li>
     * </ul>
     *
     * <p>{@link DefaultVertexFormat#NEW_ENTITY} because that is the format
     * {@code ModelBlockRenderer.renderModel} writes when it renders a single block outside chunk
     * meshing — position, colour, uv, overlay, lightmap, normal.</p>
     */
    public static RenderType ghostBlock(String name) {
        return RenderType.create(
                name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 2048, true, true,
                RenderType.CompositeState.builder()
                        .setShaderState(SHADER)
                        .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                        .setTransparencyState(TRANSLUCENT)
                        .setCullState(CULL_ENABLED)
                        .setDepthTestState(DEPTH_LEQUAL)
                        .setWriteMaskState(WRITE_COLOUR_AND_DEPTH)
                        .setLightmapState(LIGHTMAP_ON)
                        .setOverlayState(OVERLAY_ON)
                        .createCompositeState(true)
        );
    }

    private BuilderRenderStates() {
        super("dungeontrain_builder_bridge", () -> {}, () -> {});
    }
}
