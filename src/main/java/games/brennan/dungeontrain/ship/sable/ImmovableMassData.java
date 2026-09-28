package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3dc;

/**
 * The mass properties DT pushes to a locked carriage's <em>native</em> Rapier body: zero mass, zero
 * inertia, centre of mass as measured.
 *
 * <p>Rapier treats a dynamic body with zero mass as having zero <em>inverse</em> mass — contacts and
 * forces cannot change its velocity, and zero inertia does the same for its rotation — while it
 * still moves by whatever velocity it is given. That is precisely a kinematic train: DT sets the
 * body's velocity every tick and teleports it onto its pose, and nothing the body touches (a world
 * block beside the track, a neighbouring carriage at the seam, a player on the deck) may push or
 * turn it. Before this, a carriage brushing a block was shoved by the contact solver every
 * substep and teleported back every tick — visible as heavy jitter, worst on a carriage whose
 * build had moved its centre of mass.</p>
 *
 * <p>Only the native body sees this. {@code ServerSubLevel.getMassTracker()} keeps returning Sable's
 * live merged tracker, so entity-vs-carriage collision (Java-side, via
 * {@link MassData#getInverseNormalMass}) and DT's own {@code captureInertia} are unchanged.</p>
 */
public final class ImmovableMassData implements MassData {

    private static final Matrix3dc ZERO = new Matrix3d().zero();

    private final Vector3dc centerOfMass;

    public ImmovableMassData(MassData measured) {
        this.centerOfMass = measured.getCenterOfMass();
    }

    @Override public double getMass() { return 0.0; }
    @Override public double getInverseMass() { return 0.0; }
    @Override public Matrix3dc getInertiaTensor() { return ZERO; }
    @Override public Matrix3dc getInverseInertiaTensor() { return ZERO; }
    @Override public Vector3dc getCenterOfMass() { return centerOfMass; }
}
