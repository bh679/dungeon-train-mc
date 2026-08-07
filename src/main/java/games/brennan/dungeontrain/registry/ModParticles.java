package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod-side particle registry. Same {@link DeferredRegister} shape as {@link ModSounds}.
 *
 * <p><b>Why DT registers particles at all.</b> The portal door wants black smoke that does not
 * rise, and no vanilla particle is both. {@code ParticleTypes.SMOKE} tints itself a random grey and
 * carries a negative gravity (so it climbs); {@code DUST} takes a colour from the server but draws
 * the small round {@code generic_0…7} blobs, which read as soot. Colour, gravity and sprite family
 * are all decided in the particle's own constructor, so getting all three needs a type of our
 * own — see {@link games.brennan.dungeontrain.client.particle.PortalSmokeParticle}.</p>
 *
 * <p><b>No new artwork.</b> The definitions in {@code assets/dungeontrain/particles/} point at
 * vanilla's {@code big_smoke_0…11} sprites — the large ragged ones campfires plume with, which are
 * most of why campfire smoke reads as smoke. Only the behaviour is ours.</p>
 *
 * <p>Two ids rather than one because {@link SimpleParticleType} carries no parameters: the wisps and
 * the haze differ in size, opacity and lifetime, so they arrive as two registrations sharing one
 * particle class.</p>
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(Registries.PARTICLE_TYPE, DungeonTrain.MOD_ID);

    /**
     * The wisps: what peels off the door and drifts into the corridor.
     *
     * <p>{@code false} is {@code overrideLimiter} — this respects the client's particle setting like
     * any ordinary particle. It is atmosphere, and a player who has asked for fewer particles should
     * get fewer.</p>
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> PORTAL_SMOKE =
        PARTICLE_TYPES.register("portal_smoke", () -> new SimpleParticleType(false));

    /** The body: big, faint, near-stationary, sitting in the doorway itself. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> PORTAL_HAZE =
        PARTICLE_TYPES.register("portal_haze", () -> new SimpleParticleType(false));

    private ModParticles() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
