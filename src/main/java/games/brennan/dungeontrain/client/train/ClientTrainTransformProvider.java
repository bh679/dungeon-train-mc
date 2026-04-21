package games.brennan.dungeontrain.client.train;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ClientShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

/**
 * Client-side companion to {@code TrainTransformProvider}. Pins the ship's
 * {@code positionInModel} (PIM) to whatever value VS reports on the first tick
 * we see, and adjusts the world-space position to keep voxel rendering stable
 * relative to that locked PIM — regardless of what VS does with PIM between
 * ticks on the client.
 *
 * <p>Why: live logs on 0.10.1 showed that the server's PIM-drift compensation
 * produces a transform where rendering is stable in the locked-PIM frame, but
 * the client's visible rendering still shows a brief ~7-block backwards jump
 * when a FLATBED carriage enters/leaves the rolling window. Most likely cause
 * is a client-side async update to {@code ShipTransform.positionInModel} (physics
 * thread, inertia recalc, or interpolation buffer) that briefly desyncs the
 * transform the client renders with from the transform the server pushed.
 *
 * <p>By intercepting the render-path transform calls
 * ({@link #provideNextTransform} / {@link #provideNextRenderTransform}) and
 * forcing the rendered PIM to a stable value, we close that window: no matter
 * what incoming PIM VS hands us, we re-adjust position so world-space voxel
 * rendering = {@code canonicalPos + rot · (S − lockedPIM)} — the same
 * invariant the server enforces.
 *
 * <p>Math: if renderer computes {@code W = pos + rot · (S − PIM)}, then setting
 * {@code forcedPos = pos − rot · (incomingPIM − lockedPIM)} and
 * {@code forcedPIM = lockedPIM} gives {@code W = forcedPos + rot · (S − lockedPIM)
 *   = pos + rot · (S − incomingPIM)} — visually identical to the unmolested
 * render, but now PIM is stable so any async PIM drift on the client becomes
 * invisible.
 */
public final class ClientTrainTransformProvider implements ClientShipTransformProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final long shipId;
    private Vector3dc lockedPIM;
    private Quaterniondc lockedRotation;
    private long logTickCounter;

    public ClientTrainTransformProvider(long shipId) {
        this.shipId = shipId;
    }

    public boolean isLocked() {
        return lockedPIM != null;
    }

    public Vector3dc getLockedPIM() {
        return lockedPIM;
    }

    @NotNull
    @Override
    public BodyTransform provideNextTransform(
        @NotNull ShipTransform prev,
        @NotNull ShipTransform current,
        @NotNull ShipTransform next
    ) {
        captureBaseline(current);
        return stabilize(current);
    }

    @NotNull
    @Override
    public BodyTransform provideNextRenderTransform(
        @NotNull ShipTransform prev,
        @NotNull ShipTransform current,
        double alpha
    ) {
        captureBaseline(current);
        // Smoothly interpolate position between prev and current for render,
        // but keep PIM locked — PIM is an anchor, not something to tween.
        Vector3d interpPos = new Vector3d(prev.getPosition()).lerp(current.getPosition(), alpha);
        Vector3d interpPIM = new Vector3d(prev.getPositionInModel()).lerp(current.getPositionInModel(), alpha);
        return stabilizeRaw(interpPos, interpPIM, current);
    }

    private void captureBaseline(ShipTransform current) {
        if (lockedPIM == null) {
            lockedPIM = new Vector3d(current.getPositionInModel());
            lockedRotation = new Quaterniond(current.getRotation());
            LOGGER.info(
                "[DungeonTrain:clientProvider] shipId={} captured baseline lockedPIM=({}, {}, {}) lockedRot=({}, {}, {}, {})",
                shipId,
                lockedPIM.x(), lockedPIM.y(), lockedPIM.z(),
                lockedRotation.x(), lockedRotation.y(), lockedRotation.z(), lockedRotation.w()
            );
        }
    }

    private BodyTransform stabilize(ShipTransform incoming) {
        return stabilizeRaw(
            new Vector3d(incoming.getPosition()),
            new Vector3d(incoming.getPositionInModel()),
            incoming
        );
    }

    private BodyTransform stabilizeRaw(Vector3d incomingPos, Vector3d incomingPIM, ShipTransform template) {
        Vector3d delta = new Vector3d(incomingPIM).sub(lockedPIM);
        lockedRotation.transform(delta);
        Vector3d forcedPos = new Vector3d(incomingPos).sub(delta);

        // Sample-log the delta magnitude once every ~5 seconds (at 20Hz logic
        // tick, 100 logic ticks ≈ 5s; provideNextTransform fires per render
        // frame though — so this is roughly every few hundred frames at 60+FPS).
        if ((logTickCounter++ & 0x1FF) == 0) {
            Vector3d worldDelta = new Vector3d(incomingPIM).sub(lockedPIM);
            LOGGER.info(
                "[DungeonTrain:clientProvider] shipId={} incomingPIM=({}, {}, {}) lockedPIM=({}, {}, {}) " +
                "modelDelta=({}, {}, {}) forcedPos=({}, {}, {}) incomingPos=({}, {}, {})",
                shipId,
                incomingPIM.x, incomingPIM.y, incomingPIM.z,
                lockedPIM.x(), lockedPIM.y(), lockedPIM.z(),
                worldDelta.x, worldDelta.y, worldDelta.z,
                forcedPos.x, forcedPos.y, forcedPos.z,
                incomingPos.x, incomingPos.y, incomingPos.z
            );
        }

        return template.toBuilder()
            .position(forcedPos)
            .positionInModel(lockedPIM)
            .rotation(lockedRotation)
            .build();
    }
}
