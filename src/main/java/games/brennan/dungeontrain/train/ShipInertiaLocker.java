package games.brennan.dungeontrain.train;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-based utility that pins the center-of-mass, mass, and moment-of-inertia
 * on a {@link LoadedServerShip} so VS does not move the ship's {@code positionInModel}
 * when carriage blocks are added or removed.
 *
 * <h2>Why</h2>
 * VS 2.4.11 mutates {@code ShipInertiaDataImpl._centerOfMassInShip} and {@code _mass}
 * every time a block is set on a ship (via {@code onSetBlock}). On the next physics
 * tick it reads {@code getCenterOfMass()} to update {@code positionInModel}. For a
 * rolling-window train this produces a ~10-block pivot drift per carriage shift,
 * which our compensation turns into a visible hop of the ship's rendered origin.
 *
 * <h2>How</h2>
 * At spawn we snapshot the initial inertia values. After every voxel mutation we
 * write those snapshot values back into the ship's inertia data, mutating the
 * live {@link Vector3d} and {@link Matrix3d} in place (the reference fields are
 * {@code final} in VS but the contained objects are mutable). Reflection is done
 * lazily and the field handles are cached; the per-call cost is effectively a
 * field read and a vector copy.
 *
 * <h2>Scope</h2>
 * Target class is {@code org.valkyrienskies.core.impl.game.ships.ShipInertiaDataImpl}
 * in VS 2.4.11. If VS renames the class or private fields, reflection setup fails
 * gracefully at class init ({@link #reflectionReady} becomes false) and every
 * {@link #capture} / {@link #restore} call becomes a no-op with one logged error.
 * Failure mode: the train continues to hop exactly as it does without this class.
 *
 * <h2>Threading</h2>
 * Called from the server thread (by {@link TrainWindowManager#updateWindow}). The
 * mutated fields are read on the VS physics thread. Writes to a live mutable
 * {@link Vector3d} are not atomic — a concurrent physics-thread reader could see a
 * half-updated vector — but the window is ~microseconds and the value we write is
 * always the same snapshot, so the worst observable result is "unchanged" (not a
 * new bad value). Acceptable for a diagnostic pin.
 */
public final class ShipInertiaLocker {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    private static final String INERTIA_IMPL_CLASS = "org.valkyrienskies.core.impl.game.ships.ShipInertiaDataImpl";
    private static final String COM_FIELD_NAME = "_centerOfMassInShip";
    private static final String MASS_FIELD_NAME = "_mass";
    private static final String MOI_FIELD_NAME = "_momentOfInertiaTensor";

    // Reflection handles — resolved once at class init.
    private static Class<?> INERTIA_IMPL;
    private static Field COM_FIELD;
    private static Field MASS_FIELD;
    private static Field MOI_FIELD;
    private static Method GET_INERTIA_METHOD;
    private static boolean reflectionReady;

    static {
        try {
            INERTIA_IMPL = Class.forName(INERTIA_IMPL_CLASS);
            COM_FIELD = INERTIA_IMPL.getDeclaredField(COM_FIELD_NAME);
            COM_FIELD.setAccessible(true);
            MASS_FIELD = INERTIA_IMPL.getDeclaredField(MASS_FIELD_NAME);
            MASS_FIELD.setAccessible(true);
            MOI_FIELD = INERTIA_IMPL.getDeclaredField(MOI_FIELD_NAME);
            MOI_FIELD.setAccessible(true);
            reflectionReady = true;
            LOGGER.info("ShipInertiaLocker reflection ready — COM pinning active");
        } catch (Exception e) {
            LOGGER.error("ShipInertiaLocker reflection setup FAILED — COM pinning disabled, trains will still hop. VS internals may have changed.", e);
            reflectionReady = false;
        }
    }

    private ShipInertiaLocker() {}

    /**
     * Snapshot of a ship's inertia at a moment in time. Immutable — holds
     * defensive copies of the vector and matrix.
     */
    public static final class LockedInertia {
        public final Vector3dc comInShip;
        public final double mass;
        public final Matrix3dc moi;

        private LockedInertia(Vector3dc com, double m, Matrix3dc mo) {
            this.comInShip = new Vector3d(com);
            this.mass = m;
            this.moi = new Matrix3d(mo);
        }

        @Override
        public String toString() {
            return String.format("LockedInertia{com=(%.3f,%.3f,%.3f) mass=%.1f}",
                comInShip.x(), comInShip.y(), comInShip.z(), mass);
        }
    }

    /**
     * Capture the current inertia state of {@code ship}. Returns {@code null}
     * if reflection isn't ready or the ship doesn't expose the expected API
     * surface — callers should null-check and degrade gracefully.
     */
    public static LockedInertia capture(LoadedServerShip ship) {
        if (!reflectionReady) return null;
        try {
            Object inertia = getInertiaData(ship);
            if (inertia == null) return null;
            Vector3d com = (Vector3d) COM_FIELD.get(inertia);
            double mass = MASS_FIELD.getDouble(inertia);
            Matrix3d moi = (Matrix3d) MOI_FIELD.get(inertia);
            LockedInertia snapshot = new LockedInertia(com, mass, moi);
            LOGGER.info("ShipInertiaLocker captured {} for shipId={}", snapshot, ship.getId());
            return snapshot;
        } catch (Exception e) {
            LOGGER.error("ShipInertiaLocker.capture failed for shipId={}", ship.getId(), e);
            return null;
        }
    }

    /**
     * Overwrite the live inertia fields of {@code ship} with the snapshot values.
     * No-op if {@code locked == null} (reflection unavailable or capture failed).
     * The live {@link Vector3d} / {@link Matrix3d} instances are mutated in place
     * so any VS code holding a reference sees the restored value immediately.
     */
    public static void restore(LoadedServerShip ship, LockedInertia locked) {
        if (!reflectionReady || locked == null) return;
        try {
            Object inertia = getInertiaData(ship);
            if (inertia == null) return;
            Vector3d com = (Vector3d) COM_FIELD.get(inertia);
            com.set(locked.comInShip);
            MASS_FIELD.setDouble(inertia, locked.mass);
            Matrix3d moi = (Matrix3d) MOI_FIELD.get(inertia);
            moi.set(locked.moi);
        } catch (Exception e) {
            LOGGER.error("ShipInertiaLocker.restore failed for shipId={}", ship.getId(), e);
        }
    }

    /**
     * Resolve the ship's {@code ShipInertiaDataImpl} instance. Uses reflection on
     * the method name so we don't need to compile against the internal Ship type.
     * Cached after first successful resolution.
     */
    private static Object getInertiaData(LoadedServerShip ship) throws Exception {
        if (GET_INERTIA_METHOD == null) {
            GET_INERTIA_METHOD = ship.getClass().getMethod("getInertiaData");
            GET_INERTIA_METHOD.setAccessible(true);
        }
        Object inertia = GET_INERTIA_METHOD.invoke(ship);
        if (inertia != null && !INERTIA_IMPL.isInstance(inertia)) {
            LOGGER.warn("Ship's inertiaData is {}, expected {} — skipping", inertia.getClass().getName(), INERTIA_IMPL_CLASS);
            return null;
        }
        return inertia;
    }
}
