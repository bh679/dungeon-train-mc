package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.generic.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Sable adapter for {@link Shipyard}. Translates Dungeon Train's port
 * abstraction onto Sable's {@link SubLevelAssemblyHelper} +
 * {@link SubLevelContainer} APIs.
 *
 * <p>Replaces the Phase 1 stub at {@code ship.vs.VsShipyard}. Sable
 * (https://github.com/ryanhcode/sable, PolyForm Shield 1.0.0) ships an
 * actively maintained NeoForge 1.21.1 build, which Valkyrien Skies does
 * not (still 1.20.1-only as of 2026-04-28).</p>
 *
 * <p>Wrapper identity: the ship-yard caches one {@link SableManagedShip}
 * per {@link ServerSubLevel} so {@code findAt} / {@code findAll} return
 * the same wrapper across calls within a tick. This matters for the
 * train code, which uses identity equality of {@link ManagedShip}
 * handles to detect duplicates while iterating.</p>
 */
public final class SableShipyard implements Shipyard {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel level;

    /**
     * Wrapper cache. Weak so that when Sable removes a {@link ServerSubLevel}
     * (after {@code markRemoved} + container tick), the corresponding
     * {@link SableManagedShip} entry can be GC'd without manual cleanup.
     */
    private final WeakHashMap<ServerSubLevel, SableManagedShip> wrappers = new WeakHashMap<>();

    public SableShipyard(ServerLevel level) {
        this.level = level;
    }

    @Override
    public ManagedShip assemble(Set<BlockPos> blocks, double density) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Cannot assemble an empty block set");
        }

        // Sable's API takes:
        //   anchor — a single BlockPos that ends up at the centre of the
        //            sub-level's plot (model-space origin).
        //   bounds — a world-space BoundingBox3ic for moving entities and
        //            tracking points along with the assembled blocks.
        // We compute both from the input set: anchor = AABB centre rounded
        // to BlockPos; bounds = exact integer AABB.
        BlockPos anchor = computeAnchor(blocks);
        BoundingBox3i bounds = computeBounds(blocks);

        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(
            level, anchor, blocks, bounds);

        // Sable computes mass automatically from block types via MassTracker.
        // The `density` arg is informational for our adapter only.
        if (density > 0.0 && LOGGER.isTraceEnabled()) {
            LOGGER.trace("[Sable] Assembled {} blocks; ignoring requested density {}",
                blocks.size(), density);
        }

        return wrappers.computeIfAbsent(subLevel, SableManagedShip::new);
    }

    @Override
    public void delete(ManagedShip ship) {
        if (!(ship instanceof SableManagedShip sableShip)) {
            LOGGER.warn("[Sable] delete called with non-Sable ManagedShip: {}", ship);
            return;
        }
        sableShip.subLevel().markRemoved();
        // The container's per-tick removal pass picks this up next tick and
        // also clears our weak cache entry once the ServerSubLevel is GC'd.
    }

    @Override
    public List<ManagedShip> findAll() {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return List.of();
        }
        List<ManagedShip> out = new ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()) {
                out.add(wrappers.computeIfAbsent(server, SableManagedShip::new));
            }
        }
        return out;
    }

    @Override
    @Nullable
    public ManagedShip findAt(BlockPos pos) {
        SubLevel sub = Sable.HELPER.getContaining(level, pos);
        if (!(sub instanceof ServerSubLevel server) || server.isRemoved()) {
            return null;
        }
        return wrappers.computeIfAbsent(server, SableManagedShip::new);
    }

    /**
     * Axes locked between adjacent carriage sub-levels. {@code LINEAR_X}
     * is intentionally omitted so each carriage's kinematic driver can
     * advance it independently along the train's velocity direction.
     */
    private static final Set<ConstraintJointAxis> LOCKED_AXES = EnumSet.of(
        ConstraintJointAxis.LINEAR_Y,
        ConstraintJointAxis.LINEAR_Z,
        ConstraintJointAxis.ANGULAR_X,
        ConstraintJointAxis.ANGULAR_Y,
        ConstraintJointAxis.ANGULAR_Z);

    @Override
    public void lockAdjacentYZRotation(ManagedShip a, ManagedShip b) {
        if (!(a instanceof SableManagedShip sa) || !(b instanceof SableManagedShip sb)) {
            LOGGER.warn("[Sable] lockAdjacentYZRotation called with non-Sable ships: a={} b={}", a, b);
            return;
        }
        SubLevelPhysicsSystem physics = SubLevelPhysicsSystem.get(level);
        if (physics == null) {
            LOGGER.warn("[Sable] lockAdjacentYZRotation: no physics system for level {}", level.dimension().location());
            return;
        }
        PhysicsPipeline pipeline = physics.getPipeline();
        if (pipeline == null) {
            LOGGER.warn("[Sable] lockAdjacentYZRotation: physics system has no pipeline yet");
            return;
        }

        // Anchor points at each sub-level's pose origin (model-space (0,0,0))
        // with identity orientation. With LINEAR_Y/Z locked, the constraint
        // forces each body's pose.position.y/z to match the other; with
        // ANGULAR_X/Y/Z locked, both bodies' rotations stay equal. The free
        // LINEAR_X axis lets each body's canonicalPos advance independently.
        GenericConstraintConfiguration config = new GenericConstraintConfiguration(
            new Vector3d(0, 0, 0),
            new Vector3d(0, 0, 0),
            new Quaterniond(),
            new Quaterniond(),
            LOCKED_AXES);

        try {
            pipeline.addConstraint(sa.subLevel(), sb.subLevel(), config);
            LOGGER.debug("[Sable] Locked Y/Z/rotation between sub-levels {} and {}",
                sa.id(), sb.id());
        } catch (Throwable t) {
            LOGGER.warn("[Sable] addConstraint failed between {} and {}: {}",
                sa.id(), sb.id(), t.toString());
        }
    }

    /** Centre of the block set's integer AABB, rounded down to a {@link BlockPos}. */
    private static BlockPos computeAnchor(Set<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        return new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    /** Inclusive integer AABB of the block set. */
    private static BoundingBox3i computeBounds(Set<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        return new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
