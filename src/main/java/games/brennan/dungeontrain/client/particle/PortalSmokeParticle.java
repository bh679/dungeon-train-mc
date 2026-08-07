package games.brennan.dungeontrain.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The black smoke leaking past a portal corridor's room-facing door — both the wisps that drift out
 * of the frame and the haze that sits in it.
 *
 * <p>Written rather than reused because the three things that make this look right are each fixed in
 * a vanilla particle's own constructor, and no vanilla particle has all three:</p>
 * <ul>
 *   <li><b>Black.</b> {@code SmokeParticle} tints itself a random grey capped at {@code 0.3}, which
 *       no server call can override. {@code DUST} takes a colour but brings the wrong sprites.</li>
 *   <li><b>No rise.</b> Smoke and campfire smoke both carry a gravity that makes them climb. Smoke
 *       that climbs reads as a fire behind the door; this should read as something heavy getting
 *       past it.</li>
 *   <li><b>Smoke-shaped.</b> The {@code big_smoke_0…11} sprites this draws — vanilla's, via
 *       {@code assets/dungeontrain/particles/}, so there is no new artwork — are large, ragged and
 *       soft. The {@code generic_0…7} family that {@code smoke} and {@code dust} share is small and
 *       round, and reads as soot however it is coloured.</li>
 * </ul>
 *
 * <p>Movement is left entirely to whatever velocity arrives: gravity is zero, so
 * {@link games.brennan.dungeontrain.portal.PortalDoorSmoke} is the only thing deciding where the
 * smoke goes, and the two copies of a corridor cannot drift apart because of anything happening
 * here.</p>
 */
@OnlyIn(Dist.CLIENT)
public class PortalSmokeParticle extends TextureSheetParticle {

    /**
     * Not quite zero. A pure black quad reads as a hole punched in the world — it is the one colour
     * nothing else in a lit corridor is — where a very dark grey still shows its own shape against
     * the stone.
     */
    private static final float SHADE = 0.03f;

    /** Fraction of a particle's life spent fading in, so nothing appears out of nowhere. */
    private static final float FADE_IN = 0.15f;

    /** Fraction spent fading out. Longer than the fade in: smoke thins away rather than stopping. */
    private static final float FADE_OUT = 0.45f;

    /**
     * One look. Both registered types are this class; only these numbers differ.
     *
     * @param scale    quad size multiplier
     * @param alpha    opacity at full strength
     * @param lifetime base lifetime in ticks, before the per-particle spread
     * @param friction per-tick velocity decay — how quickly the drift dies away
     */
    public record Profile(float scale, float alpha, int lifetime, float friction) {}

    /** The wisps: small, fairly solid, gone in a couple of seconds. */
    public static final Profile WISP = new Profile(0.7f, 0.85f, 50, 0.94f);

    /**
     * The haze: big, faint and long-lived, spawned in the doorway itself.
     *
     * <p>The volume is an illusion of overlap — several large translucent camera-facing quads
     * stacked in the frame at once. Hence the low alpha: at this size a couple of them at wisp
     * opacity would be a black wall rather than a haze.</p>
     */
    public static final Profile HAZE = new Profile(3.0f, 0.22f, 90, 0.97f);

    private final SpriteSet sprites;
    private final Profile profile;

    protected PortalSmokeParticle(ClientLevel level, double x, double y, double z,
                                  double xSpeed, double ySpeed, double zSpeed,
                                  Profile profile, SpriteSet sprites) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.sprites = sprites;
        this.profile = profile;

        // Taken verbatim rather than scaled by a random factor the way vanilla smoke does: the
        // server sends the exact velocity both copies of the corridor are to use, and the whole
        // point of sending it exactly is that the two look the same.
        this.xd = xSpeed;
        this.yd = ySpeed;
        this.zd = zSpeed;

        this.gravity = 0.0f;
        this.friction = profile.friction();
        this.hasPhysics = true;
        this.speedUpWhenYMotionIsBlocked = false;

        this.quadSize *= profile.scale();
        this.lifetime = Math.max(1, (int) (profile.lifetime() * (this.random.nextFloat() * 0.4f + 0.8f)));
        this.setColor(SHADE, SHADE, SHADE);
        this.setAlpha(0.0f);
        this.setSpriteFromAge(sprites);
    }

    /** Translucent, so the haze can be seen through and stacked quads add up rather than replace. */
    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.removed) return;
        this.setSpriteFromAge(this.sprites);
        this.setAlpha(alphaAt((float) this.age / this.lifetime));
    }

    /**
     * Opacity across a particle's life: up over the first {@link #FADE_IN}, flat, then away over the
     * last {@link #FADE_OUT}. Smoke that appeared and vanished at full strength would strobe, which
     * is exactly the sort of thing a player would notice at the moment of a portal swap.
     */
    private float alphaAt(float progress) {
        float ramp = progress < FADE_IN
            ? progress / FADE_IN
            : progress > 1.0f - FADE_OUT
                ? (1.0f - progress) / FADE_OUT
                : 1.0f;
        return profile.alpha() * Mth.clamp(ramp, 0.0f, 1.0f);
    }

    /** Provider for one profile — registered once per particle type. */
    @OnlyIn(Dist.CLIENT)
    public record Provider(Profile profile, SpriteSet sprites)
        implements ParticleProvider<SimpleParticleType> {

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new PortalSmokeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, profile, sprites);
        }
    }
}
