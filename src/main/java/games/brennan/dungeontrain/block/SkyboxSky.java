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
     * No sky of its own: the hole simply <em>reveals</em> the sky vanilla already drew this
     * frame. The original, cheapest variant — the depth punch is its entire implementation, so
     * it needs neither a stencil ref nor a sky pass.
     *
     * <p>Because it shows the live sky, it inherits vanilla's behaviour exactly, including the
     * black void plane below the horizon. Underground it shows sky looking up and black
     * looking down. That is faithful rather than broken — use {@link #SURFACE} when you want
     * the above-ground sky regardless of depth.</p>
     */
    LIVE("skybox_block", 1, false),

    /**
     * The overworld sky as it looks from above ground, at any Y. Vanilla's own
     * {@code renderSky} re-run with its void plane suppressed — see {@code SkyboxStencil}
     * for why that one suppression is the whole difference.
     */
    SURFACE("skybox_surface", 2, true),

    /** The End starfield, from {@link games.brennan.dungeontrain.client.VoidSkyRenderer}. */
    END("skybox_end", 3, true),

    /** The Nether fog-colour fill, from {@link games.brennan.dungeontrain.client.NetherSkyRenderer}. */
    NETHER("skybox_nether", 4, true),

    /**
     * The upside-down band's sky — day-blue dome with the sun and moon orbiting the horizon,
     * from {@link games.brennan.dungeontrain.client.UpsideDownSkyRenderer}.
     */
    UPSIDE_DOWN("skybox_upside_down", 5, true),

    /**
     * Night on both halves of the day — stars wheeling overhead and the moon crossing, but the
     * sun never drawn and so no sunrise at all, from
     * {@link games.brennan.dungeontrain.client.NightSkyRenderer}.
     */
    NIGHT("skybox_night", 6, true),

    /**
     * A permanent dawn that sweeps sideways the way {@link #UPSIDE_DOWN} does — sun and glow
     * orbiting the horizon, moon opposite, stars turning with them, from
     * {@link games.brennan.dungeontrain.client.SunriseSkyRenderer}.
     */
    SUNRISE("skybox_sunrise", 7, true);

    public static final Codec<SkyboxSky> CODEC = StringRepresentable.fromEnum(SkyboxSky::values);

    private final String blockName;
    private final int stencilRef;
    private final boolean ownSky;

    SkyboxSky(String blockName, int stencilRef, boolean ownSky) {
        this.blockName = blockName;
        this.stencilRef = stencilRef;
        this.ownSky = ownSky;
    }

    /** Registry path of the block that shows this sky. */
    public String blockName() {
        return blockName;
    }

    /** Stencil value marking this variant's holes. Never 0. */
    public int stencilRef() {
        return stencilRef;
    }

    /**
     * Does this variant draw a sky of its own, needing a stencil mask and a sky pass?
     * {@code false} for {@link #LIVE}, which is satisfied by the depth punch alone.
     */
    public boolean hasOwnSky() {
        return ownSky;
    }

    @Override
    public String getSerializedName() {
        return blockName;
    }
}
