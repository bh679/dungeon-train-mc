package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Plays {@link PortalDoorSmoke} into the world — the same seep, in both copies of a corridor.
 *
 * <p><b>Black dust, not smoke.</b> The obvious choice, {@code ParticleTypes.SMOKE}, is wrong twice
 * over and neither fault can be corrected from the server: {@code SmokeParticle} tints itself a
 * <i>random grey</i> capped at {@code 0.3}, and it carries a negative gravity, which in
 * {@code Particle.tick} means it climbs. Grey smoke rising off a door frame reads as a chimney.</p>
 *
 * <p>{@code DUST} is the same picture without either problem. Its particle definition uses the very
 * same {@code generic_0…7} sprites {@code smoke} does — so it is, texture for texture, a puff of
 * smoke — but the colour comes from the server, {@code DustParticle} multiplies it rather than
 * randomising it (so {@link #BLACK} stays exactly black), and {@code DustParticleBase} sets no
 * gravity at all, leaving the drift entirely to us.</p>
 *
 * <p><b>The carriage copy is emitted at plot coordinates, not world ones.</b> A corridor riding the
 * train is a Sable sub-level: its blocks live in a plot far from where the player sees them, and the
 * ship's pose is what puts them on screen. Sable's particle mixins do the same for particles —
 * {@code ParticleMixin.sable$initialKickOut} transforms one spawned inside a plot into the ship's
 * pose and has it track the sub-level from then on, and {@code ServerLevelMixin} makes the
 * send-distance check sub-level-aware so it reaches the player at all. Emitting at the world
 * position the player sees would instead leave a trail of smoke hanging in the air behind a moving
 * train.</p>
 *
 * <p>The twin stands still in ordinary world space and needs none of that.</p>
 */
public final class PortalDoorSmokeEmitter {

    /**
     * Passed as the particle count. <b>Zero is not "none"</b> — it is vanilla's single-particle form,
     * the only one that carries an exact position and an exact velocity to the client. With a
     * positive count the client randomises both around the values sent, which would make the two
     * copies drift differently and cost the seep its direction.
     */
    private static final int SINGLE = 0;

    /** Pure black. {@code DustParticle} multiplies this by its randomised shade, and zero stays zero. */
    private static final Vector3f BLACK = new Vector3f(0.0f, 0.0f, 0.0f);

    /**
     * Particle size. Around one and a half times a redstone mote, which is roughly the size a puff of
     * vanilla smoke comes out at — big enough to read as smoke rather than soot, small enough that
     * three of them do not curtain off the doorway. It also stretches the particle's life, so the
     * seep is still visible by the time it has crawled clear of the frame.
     */
    private static final float SCALE = 1.6f;

    /**
     * Velocity multiplier, and not a free parameter: {@code DustParticleBase} multiplies the speed it
     * is handed by {@code 0.1} before using it, so it must be handed ten times the drift
     * {@link PortalDoorSmoke} asks for. Anything less and the smoke barely leaves the door.
     */
    private static final double SPEED = 10.0;

    private PortalDoorSmokeEmitter() {}

    /**
     * Emit this tick's smoke at the portal-ward door of both copies of {@code frames}' corridor.
     *
     * <p>Called from the portal carriage tick, which has already established that somebody is near
     * enough for the pair to be live — an unattended corridor emits nothing.</p>
     */
    public static void emit(ServerLevel level, PortalFrames frames, ManagedShip ship) {
        PortalDoorSmoke.Emission emission =
            new PortalDoorSmoke(frames.layout(), frames.role()).emissionOn(level.getGameTime());
        if (emission == null) return;

        sendAt(level, frames.originOf(PortalFrames.FRAME_CARRIAGE), emission, ship);
        sendAt(level, frames.originOf(PortalFrames.FRAME_TWIN), emission, null);
    }

    /**
     * One particle at a corridor-local point in the frame based at {@code origin}, converted into the
     * ship's plot when there is one.
     *
     * @param ship the carriage this frame rides, or {@code null} for the static twin
     */
    private static void sendAt(ServerLevel level, PortalFrames.Origin origin,
                               PortalDoorSmoke.Emission emission, @Nullable ManagedShip ship) {
        double x = origin.x() + emission.x();
        double y = origin.y() + emission.y();
        double z = origin.z() + emission.z();
        double vx = emission.vx();
        double vy = emission.vy();
        double vz = emission.vz();

        if (ship != null) {
            // Both the point and the velocity go through the ship's own transform rather than an
            // assumed axis mapping — the same reasoning as PortalPairIndex.Entry.plotPosOf. The
            // velocity is converted as the difference between two transformed points, because
            // worldToShip maps positions: transforming a bare direction would add the translation.
            Vector3d plot = ship.worldToShip(new Vector3d(x, y, z));
            Vector3d plotAhead = ship.worldToShip(new Vector3d(x + vx, y + vy, z + vz));
            vx = plotAhead.x - plot.x;
            vy = plotAhead.y - plot.y;
            vz = plotAhead.z - plot.z;
            x = plot.x;
            y = plot.y;
            z = plot.z;
        }

        level.sendParticles(new DustParticleOptions(BLACK, SCALE), x, y, z, SINGLE, vx, vy, vz, SPEED);
    }
}
