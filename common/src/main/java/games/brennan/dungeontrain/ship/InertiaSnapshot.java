package games.brennan.dungeontrain.ship;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Immutable snapshot of a ship's inertia state — center of mass, total
 * mass, and moment-of-inertia tensor. Held by Dungeon Train so the
 * rolling-window manager can pin these values across voxel mutations
 * (otherwise the underlying physics mod recomputes COM after every block
 * change, drifting the ship's pivot and producing visible hops).
 */
public final class InertiaSnapshot {

    private final Vector3dc comInShip;
    private final double mass;
    private final Matrix3dc moiTensor;

    /**
     * Defensive-copy constructor — callers may reuse their {@code com} and
     * {@code moi} instances after construction without aliasing this
     * snapshot's state.
     */
    public InertiaSnapshot(Vector3dc com, double mass, Matrix3dc moi) {
        this.comInShip = new Vector3d(com);
        this.mass = mass;
        this.moiTensor = new Matrix3d(moi);
    }

    public Vector3dc comInShip() {
        return comInShip;
    }

    public double mass() {
        return mass;
    }

    public Matrix3dc moiTensor() {
        return moiTensor;
    }

    /**
     * Return a new snapshot whose {@link #comInShip} is shifted by
     * {@code delta}. Mass and MOI carry over unchanged. Used by the
     * pivot-shift workaround to advance a train's reference frame.
     */
    public InertiaSnapshot shifted(Vector3dc delta) {
        Vector3d shifted = new Vector3d(comInShip).add(delta);
        return new InertiaSnapshot(shifted, mass, moiTensor);
    }

    @Override
    public String toString() {
        return String.format("InertiaSnapshot{com=(%.3f,%.3f,%.3f) mass=%.1f}",
            comInShip.x(), comInShip.y(), comInShip.z(), mass);
    }
}
