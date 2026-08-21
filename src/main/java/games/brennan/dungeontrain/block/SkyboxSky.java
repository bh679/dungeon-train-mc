package games.brennan.dungeontrain.block;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * The sky a skybox block shows through its hole. One value per registered block.
 *
 * <p>{@link #stencilRef} is the value written into the stencil buffer where that variant's
 * cubes are, and tested against when its sky is drawn — it is what keeps two different
 * variants side by side showing two different skies. Refs start at 1 because 0 is the cleared
 * value, i.e. "not a skybox hole".</p>
 *
 * <p>Ordinals are <b>not</b> used as the ref, and the refs are written out explicitly, so
 * reordering or inserting a value can't silently re-point existing blocks at another sky.</p>
 *
 * <p>Lives in the common {@code block} package, not {@code client.skybox}, because
 * {@link SkyboxBlock} references it and that class loads on a dedicated server too.</p>
 */
public enum SkyboxSky implements StringRepresentable {

    /**
     * The overworld sky as it looks from above ground, at any Y. Vanilla's own
     * {@code renderSky} re-run with its void plane suppressed — see
     * {@link SkyboxStencil} for why that one suppression is the whole difference.
     */
    SURFACE("skybox_block", 1),

    /** The End starfield, from {@link games.brennan.dungeontrain.client.VoidSkyRenderer}. */
    END("skybox_end", 2),

    /** The Nether fog-colour fill, from {@link games.brennan.dungeontrain.client.NetherSkyRenderer}. */
    NETHER("skybox_nether", 3),

    /**
     * The upside-down band's sky — day-blue dome with the sun and moon orbiting the horizon,
     * from {@link games.brennan.dungeontrain.client.UpsideDownSkyRenderer}.
     */
    UPSIDE_DOWN("skybox_upside_down", 4);

    public static final Codec<SkyboxSky> CODEC = StringRepresentable.fromEnum(SkyboxSky::values);

    private final String blockName;
    private final int stencilRef;

    SkyboxSky(String blockName, int stencilRef) {
        this.blockName = blockName;
        this.stencilRef = stencilRef;
    }

    /** Registry path of the block that shows this sky. */
    public String blockName() {
        return blockName;
    }

    /** Stencil value marking this variant's holes. Never 0. */
    public int stencilRef() {
        return stencilRef;
    }

    @Override
    public String getSerializedName() {
        return blockName;
    }
}
