package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.registry.ModParticles;
import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;

/**
 * Plays {@link PortalDoorSmoke} into the world — the same seep, in both copies of a corridor.
 *
 * <p><b>DT's own particles, not a vanilla one.</b> Three things have to be true at once — black,
 * no rise, and smoke-shaped — and each is fixed inside a vanilla particle's constructor where no
 * server call reaches. {@code SMOKE} is a random grey that climbs; {@code DUST} takes a colour from
 * the server but draws the small round {@code generic_0…7} blobs. So the look lives in
 * {@link games.brennan.dungeontrain.client.particle.PortalSmokeParticle} and the two ids in
 * {@link ModParticles}, and this class only decides which of them each emission is.</p>
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

    /**
     * Velocity multiplier — {@code 1}, because the velocity is already in blocks per tick and DT's
     * particle takes what it is handed verbatim. (Vanilla particles do not: {@code DustParticleBase},
     * for one, quietly scales the speed it is given by a tenth. Owning the particle is what removes
     * that class of surprise.)
     */
    private static final double SPEED = 1.0;

    private PortalDoorSmokeEmitter() {}

    /**
     * Emit this tick's smoke at the portal-ward door of both copies of {@code frames}' corridor.
     *
     * <p>Called from the portal carriage tick, which has already established that somebody is near
     * enough for the pair to be live — an unattended corridor emits nothing.</p>
     */
    public static void emit(ServerLevel level, PortalFrames frames, ManagedShip ship) {
        List<PortalDoorSmoke.Emission> emissions =
            new PortalDoorSmoke(frames.layout(), frames.role()).emissionsOn(level.getGameTime());
        if (emissions.isEmpty()) return;

        for (PortalDoorSmoke.Emission emission : emissions) {
            sendAt(level, frames.originOf(PortalFrames.FRAME_CARRIAGE), emission, ship);
            sendAt(level, frames.originOf(PortalFrames.FRAME_TWIN), emission, null);
        }
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

        level.sendParticles(typeOf(emission.kind()), x, y, z, SINGLE, vx, vy, vz, SPEED);
    }

    /** The registered id behind each kind. */
    private static SimpleParticleType typeOf(PortalDoorSmoke.Kind kind) {
        return kind == PortalDoorSmoke.Kind.HAZE
            ? ModParticles.PORTAL_HAZE.get()
            : ModParticles.PORTAL_SMOKE.get();
    }
}
