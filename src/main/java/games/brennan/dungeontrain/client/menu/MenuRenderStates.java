package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Bridge class that extends {@link RenderStateShard} so the protected static
 * shard constants (shader, transparency, cull, depth, write-mask) are
 * accessible from this package. Java allows a subclass to read protected
 * static members from its parent regardless of package; re-exposing them
 * here as {@code public static final} lets {@link CommandMenuRenderer} build
 * a custom {@link net.minecraft.client.renderer.RenderType} without needing
 * an access transformer.
 *
 * <p>Never instantiated; the private constructor exists only to satisfy the
 * superclass constructor signature.</p>
 */
public final class MenuRenderStates extends RenderStateShard {

    public static final ShaderStateShard SHADER_POSITION_COLOR = POSITION_COLOR_SHADER;
    public static final TransparencyStateShard TRANSPARENCY_TRANSLUCENT = TRANSLUCENT_TRANSPARENCY;
    public static final CullStateShard CULL_DISABLED = NO_CULL;
    public static final DepthTestStateShard DEPTH_LEQUAL = LEQUAL_DEPTH_TEST;
    public static final WriteMaskStateShard WRITE_COLOR_ONLY = COLOR_WRITE;

    /**
     * The translucent flat-colour quad type every world-space panel in this mod draws with:
     * position+colour, alpha-blended, no culling (panels and washes are viewed from both sides),
     * depth-tested but not depth-writing so overlapping quads don't punch holes in each other.
     *
     * <p>Each renderer passes its own name so they stay separate batches in the buffer source.</p>
     */
    public static RenderType translucentQuad(String name) {
        return RenderType.create(
            name,
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 256, false, true,
            RenderType.CompositeState.builder()
                .setShaderState(SHADER_POSITION_COLOR)
                .setTransparencyState(TRANSPARENCY_TRANSLUCENT)
                .setCullState(CULL_DISABLED)
                .setDepthTestState(DEPTH_LEQUAL)
                .setWriteMaskState(WRITE_COLOR_ONLY)
                .createCompositeState(false)
        );
    }

    private MenuRenderStates() {
        super("dungeontrain_menu_bridge", () -> {}, () -> {});
    }
}
