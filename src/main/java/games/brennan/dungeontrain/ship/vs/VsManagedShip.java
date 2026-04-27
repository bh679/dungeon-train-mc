package games.brennan.dungeontrain.ship.vs;

import games.brennan.dungeontrain.ship.InertiaSnapshot;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

/**
 * Valkyrien Skies adapter for {@link ManagedShip}. Wraps a
 * {@link LoadedServerShip} and translates JOML ↔ VS types.
 *
 * <p>Inertia operations delegate to {@link VsInertiaLocker} which uses
 * reflection on VS internals (fragile across version bumps but isolated
 * to this package).</p>
 */
public final class VsManagedShip implements ManagedShip {

    private final ServerLevel level;
    private final ServerShip wrapped;

    VsManagedShip(ServerLevel level, ServerShip wrapped) {
        this.level = level;
        this.wrapped = wrapped;
    }

    /** Package-private accessor — used by {@link VsShipyard#delete}. */
    ServerShip wrappedAsServerShip() {
        return wrapped;
    }

    @Override
    public long id() {
        return wrapped.getId();
    }

    @Override
    public Vector3d worldToShip(Vector3d worldPos) {
        wrapped.getTransform().getWorldToShip().transformPosition(worldPos);
        return worldPos;
    }

    @Override
    public Vector3d shipToWorld(Vector3d modelPos) {
        wrapped.getTransform().getShipToWorld().transformPosition(modelPos);
        return modelPos;
    }

    @Override
    public Vector3dc currentWorldPosition() {
        return wrapped.getTransform().getPosition();
    }

    @Override
    public Quaterniondc currentRotation() {
        return wrapped.getTransform().getRotation();
    }

    @Override
    public Vector3dc currentPositionInModel() {
        return wrapped.getTransform().getPositionInModel();
    }

    @Override
    public AABBdc worldAABB() {
        return wrapped.getWorldAABB();
    }

    @Override
    @Nullable
    public KinematicDriver getKinematicDriver() {
        var provider = wrapped.getTransformProvider();
        if (provider instanceof VsKinematicDriverAdapter adapter) {
            return adapter.driver();
        }
        return null;
    }

    @Override
    public void setKinematicDriver(KinematicDriver driver) {
        wrapped.setTransformProvider(new VsKinematicDriverAdapter(driver));
    }

    @Override
    public void setStatic(boolean isStatic) {
        wrapped.setStatic(isStatic);
    }

    @Override
    public void applyTickOutput(KinematicDriver.TickOutput output) {
        ShipTransform current = wrapped.getTransform();
        BodyTransform next = current.toBuilder()
            .position(output.position())
            .rotation(output.rotation())
            .positionInModel(output.positionInModel())
            .build();
        if (wrapped instanceof LoadedServerShip loaded) {
            loaded.unsafeSetTransform(next);
        }
    }

    @Override
    @Nullable
    public InertiaSnapshot captureInertia() {
        if (wrapped instanceof LoadedServerShip loaded) {
            return VsInertiaLocker.capture(loaded);
        }
        return null;
    }

    @Override
    public void restoreInertia(@Nullable InertiaSnapshot snapshot) {
        if (snapshot != null && wrapped instanceof LoadedServerShip loaded) {
            VsInertiaLocker.restore(loaded, snapshot);
        }
    }
}
