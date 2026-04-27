package games.brennan.dungeontrain.ship.vs;

import games.brennan.dungeontrain.ship.KinematicDriver;
import org.jetbrains.annotations.NotNull;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

/**
 * Bridges a port {@link KinematicDriver} into VS's
 * {@link ServerShipTransformProvider} interface. One adapter per attached
 * driver — created in {@code VsManagedShip.setKinematicDriver}.
 *
 * <p>Translates VS's {@link ShipTransform} ↔ port
 * {@link KinematicDriver.TickInput}/{@link KinematicDriver.TickOutput}.</p>
 */
public final class VsKinematicDriverAdapter implements ServerShipTransformProvider {

    private final KinematicDriver driver;

    public VsKinematicDriverAdapter(KinematicDriver driver) {
        this.driver = driver;
    }

    /** The wrapped driver. Used by {@code VsManagedShip.getKinematicDriver}. */
    public KinematicDriver driver() {
        return driver;
    }

    @NotNull
    @Override
    public NextTransformAndVelocityData provideNextTransformAndVelocity(
        @NotNull ShipTransform prev,
        @NotNull ShipTransform current
    ) {
        KinematicDriver.TickInput input = new KinematicDriver.TickInput(
            current.getPosition(),
            current.getRotation(),
            current.getPositionInModel()
        );
        KinematicDriver.TickOutput output = driver.nextTransform(input);
        BodyTransform next = current.toBuilder()
            .position(output.position())
            .rotation(output.rotation())
            .positionInModel(output.positionInModel())
            .build();
        return new NextTransformAndVelocityData(next, output.linearVelocity(), output.angularVelocity());
    }
}
