package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.renderer.RenderStateShard;

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

    private MenuRenderStates() {
        super("dungeontrain_menu_bridge", () -> {}, () -> {});
    }
}
