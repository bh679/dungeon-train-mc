package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.ServerShipTransformProvider;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;

import java.util.HashSet;
import java.util.Set;

/**
 * Drives a VS ship at a fixed world-space velocity by prescribing its next
 * transform every physics tick — a kinematic alternative to force-based
 * physics that bypasses the VS/Bullet mass threshold we hit with
 * TrainForcesInducer at count=20.
 *
 * Entity ride-along still works: VS uses the returned {@code nextVel} to
 * compute carry-along for entities standing on the ship's blocks.
 *
 * This class is used as the Dungeon Train marker — a loaded ship whose
 * transform provider is a {@code TrainTransformProvider} is one of ours.
 * Window state (shipyard origin, carriage count, active indices) lives
 * here so the rolling-window manager can read/write it via one cast.
 */
public final class TrainTransformProvider implements ServerShipTransformProvider {

    // VS physics thread runs at 60 Hz; each call to provideNextTransformAndVelocity
    // represents one physics step.
    private static final double PHYSICS_DT = 1.0 / 60.0;
    private static final Vector3dc ZERO_OMEGA = new Vector3d();

    private final Vector3d targetVelocity;
    private final BlockPos shipyardOrigin;
    private final int count;
    private final ResourceKey<Level> dimensionKey;
    private final long seed;
    private final Set<Integer> activeIndices;
    private final Set<Integer> populatedIndices;

    // Lazily captured on the first physics tick so the ship's spawn-time
    // orientation and position become the authoritative baseline. Re-applying
    // them every tick makes the train immune to gravity and collision impulses.
    private Quaterniondc lockedRotation;
    private Vector3d canonicalPos;

    public TrainTransformProvider(
        Vector3dc targetVelocity,
        BlockPos shipyardOrigin,
        int count,
        ResourceKey<Level> dimensionKey,
        long seed
    ) {
        this.targetVelocity = new Vector3d(targetVelocity);
        this.shipyardOrigin = shipyardOrigin.immutable();
        this.count = count;
        this.dimensionKey = dimensionKey;
        this.seed = seed;
        this.activeIndices = new HashSet<>();
        this.populatedIndices = new HashSet<>();
        for (int i = 0; i < count; i++) {
            this.activeIndices.add(i);
        }
    }

    public Vector3dc getTargetVelocity() {
        return targetVelocity;
    }

    public BlockPos getShipyardOrigin() {
        return shipyardOrigin;
    }

    public int getCount() {
        return count;
    }

    public ResourceKey<Level> getDimensionKey() {
        return dimensionKey;
    }

    public long getSeed() {
        return seed;
    }

    public Set<Integer> getActiveIndices() {
        return activeIndices;
    }

    public Set<Integer> getPopulatedIndices() {
        return populatedIndices;
    }

    @NotNull
    @Override
    public NextTransformAndVelocityData provideNextTransformAndVelocity(
        @NotNull ShipTransform prev,
        @NotNull ShipTransform current
    ) {
        if (lockedRotation == null) {
            lockedRotation = new Quaterniond(current.getRotation());
            canonicalPos = new Vector3d(current.getPosition());
        }

        canonicalPos.add(
            targetVelocity.x() * PHYSICS_DT,
            targetVelocity.y() * PHYSICS_DT,
            targetVelocity.z() * PHYSICS_DT
        );

        BodyTransform nextTransform = current.toBuilder()
            .position(canonicalPos)
            .rotation(lockedRotation)
            .build();
        return new NextTransformAndVelocityData(nextTransform, targetVelocity, ZERO_OMEGA);
    }
}
