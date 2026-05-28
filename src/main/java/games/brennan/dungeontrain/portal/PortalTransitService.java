package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Cross-dimension carriage transit. Given a {@link TransitJob} describing a
 * carriage that just crossed a portal plane, this service captures the
 * carriage state, materialises it in the target dimension, re-attaches the
 * Dungeon Train kinematic driver, and migrates any player passengers via
 * vanilla {@link DimensionTransition}.
 *
 * <h2>Steps performed by {@link #transit}</h2>
 * <ol>
 *   <li>Resolve target {@link ServerLevel}; verify partner chunks are
 *       pre-warmed (re-queue otherwise).</li>
 *   <li>Snapshot player passengers in the carriage's world AABB.</li>
 *   <li>Write the {@link TrainTransformProvider} state into the source
 *       {@link ServerSubLevel}'s {@code userDataTag} so it survives the
 *       round trip.</li>
 *   <li>Serialize source via {@link SubLevelSerializer#toData}.</li>
 *   <li>Reload into target via {@link SubLevelSerializer#fullyLoad}.</li>
 *   <li>Locate the new sub-level's wrapper in the target shipyard, read
 *       back the provider state, attach via
 *       {@link TrainAssembler#attachDriver}.</li>
 *   <li>Teleport players via {@link ServerPlayer#changeDimension}; vanilla
 *       handles the loading-screen flash on the client.</li>
 *   <li>Delete the source carriage via {@code Shipyards.delete} (uses
 *       Sable's existing {@code markRemoved} flow).</li>
 * </ol>
 *
 * <h2>v1 limitations</h2>
 * <ul>
 *   <li>Partner uses the SAME world coordinates as the source (no Nether
 *       1:8 scaling). The pose is transferred unchanged.</li>
 *   <li>Player passengers are detected by AABB intersection — exotic seating
 *       arrangements (player standing on a bridge between carriages) may
 *       cross the AABB boundary and miss the migration. They can still
 *       cross on foot via {@code PlayerPortalCrossListener} (Phase 10).</li>
 *   <li>Non-player entities riding the carriage are NOT migrated. They are
 *       lost in the source dim. Deferred to v2.</li>
 *   <li>If Sable's {@code fullyLoad} fails (target dim corrupt, chunk
 *       generation error), the source carriage is NOT deleted — the train
 *       keeps moving through the portal, which is the safest fallback.</li>
 * </ul>
 */
public final class PortalTransitService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One unit of work: a carriage that should transit from
     * {@code (sourceLevel, sourcePortalPos)} to {@code (targetDim, targetPortalPos)}.
     * Created by {@code CarriageTransitDetector} (Phase 9).
     */
    public record TransitJob(
        ServerLevel sourceLevel,
        Trains.Carriage carriage,
        BlockPos sourcePortalPos,
        ResourceKey<Level> targetDim,
        BlockPos targetPortalPos
    ) {}

    private PortalTransitService() {}

    /**
     * Execute a single transit job. Returns {@code true} on success (the
     * carriage now lives in the target dim and the source has been
     * destroyed). Returns {@code false} if execution was deferred — caller
     * may retry on a later tick.
     */
    public static boolean transit(TransitJob job) {
        ServerLevel sourceLevel = job.sourceLevel;
        ServerLevel targetLevel = sourceLevel.getServer().getLevel(job.targetDim);
        if (targetLevel == null) {
            LOGGER.warn("[Portal] Transit aborted — target dim {} unresolved", job.targetDim.location());
            return false;
        }

        // Defer if partner chunks aren't loaded yet. Spawner pre-warms them
        // at pair creation, but a freshly-restarted server may need a tick
        // or two for the chunks to actually reach FULL status.
        if (!ChunkPrewarmer.isReady(targetLevel, new ChunkPos(job.targetPortalPos), 3)) {
            return false;
        }

        if (!(job.carriage.ship() instanceof SableManagedShip sableSource)) {
            LOGGER.warn("[Portal] Transit aborted — non-Sable ship {}", job.carriage.ship().id());
            return false;
        }
        ServerSubLevel sourceSubLevel = sableSource.subLevel();

        // 1. Snapshot players in this carriage's AABB before any serialization.
        //    We re-mount them on the target side after fullyLoad.
        AABBdc worldAabb = sableSource.worldAABB();
        AABB box = new AABB(
            worldAabb.minX(), worldAabb.minY(), worldAabb.minZ(),
            worldAabb.maxX(), worldAabb.maxY(), worldAabb.maxZ());
        List<ServerPlayer> passengers = new ArrayList<>(sourceLevel.getPlayers(p -> box.contains(p.position())));

        // 2. Bake provider state into userDataTag so it survives toData/fullyLoad.
        TrainTransformProvider sourceProvider = job.carriage.provider();
        CompoundTag userData = sourceSubLevel.getUserDataTag();
        if (userData == null) {
            userData = new CompoundTag();
        }
        sourceProvider.writeToUserDataTag(userData);
        sourceSubLevel.setUserDataTag(userData);

        // 3. Serialize.
        SubLevelData data;
        try {
            data = SubLevelSerializer.toData(sourceSubLevel, Collections.emptyList());
        } catch (Throwable t) {
            LOGGER.error("[Portal] SubLevelSerializer.toData threw — keeping source carriage in place", t);
            return false;
        }

        // 4. Reload in target dim. Pose stays unchanged (partner uses same world coords in v1).
        ServerSubLevel newSubLevel;
        try {
            newSubLevel = SubLevelSerializer.fullyLoad(targetLevel, data);
        } catch (Throwable t) {
            LOGGER.error("[Portal] SubLevelSerializer.fullyLoad threw — keeping source carriage in place", t);
            return false;
        }
        if (newSubLevel == null) {
            LOGGER.warn("[Portal] fullyLoad returned null — keeping source carriage in place");
            return false;
        }

        // 5. Find the ManagedShip wrapper for the new sub-level. Sable's
        //    shipyard caches wrappers per sub-level, so findAll() will
        //    surface our new sub-level wrapped. Match by UUID (preserved
        //    across serialization round-trip).
        ManagedShip newShip = null;
        UUID newId = newSubLevel.getUniqueId();
        for (ManagedShip s : Shipyards.of(targetLevel).findAll()) {
            if (s.subLevelId().equals(newId)) {
                newShip = s;
                break;
            }
        }
        if (newShip == null) {
            LOGGER.warn("[Portal] Couldn't find ManagedShip wrapper for transited sub-level uuid={} — driver not attached",
                newId);
            // Don't bail — the sub-level exists in the target dim, just driverless.
            // Better than rolling back; the player can break out manually.
        } else {
            // 6. Reconstruct provider from userDataTag in the new sub-level.
            CompoundTag newUserData = newSubLevel.getUserDataTag();
            TrainTransformProvider newProvider = null;
            if (newUserData != null) {
                newProvider = TrainTransformProvider.readFromUserDataTag(newUserData, job.targetDim);
            }
            if (newProvider == null) {
                LOGGER.warn("[Portal] No provider data round-tripped on uuid={} — carriage now driverless",
                    newId);
            } else {
                // pendingEntities = null: transit doesn't respawn content
                // entities, they're physically (or logically) inherited.
                TrainAssembler.attachDriver(targetLevel, newShip, newProvider, null);
            }
        }

        // 7. Migrate players. Same world coords means same Vec3 in target dim.
        //    Vanilla changeDimension causes the unavoidable "Loading terrain"
        //    flash on the client — v1 accepts this; v2 may add IP integration
        //    once Sable issue #155 is resolved upstream.
        for (ServerPlayer player : passengers) {
            Vec3 sourcePos = player.position();
            DimensionTransition transition = new DimensionTransition(
                targetLevel, sourcePos, player.getDeltaMovement(),
                player.getYRot(), player.getXRot(),
                DimensionTransition.DO_NOTHING);
            try {
                player.changeDimension(transition);
            } catch (Throwable t) {
                LOGGER.error("[Portal] Player dimension transition threw for {} — player may be in inconsistent state",
                    player.getName().getString(), t);
            }
        }

        // 8. Delete the source carriage. SableShipyard.delete uses markRemoved
        //    which routes through Sable's HoldingSubLevel flow — safe and
        //    GC-friendly. The Trains registry entry for the source dim is
        //    cleaned up by the appender's ghost-anchor pass next time it ticks.
        Shipyards.of(sourceLevel).delete(sableSource);

        LOGGER.info("[Portal] Transited carriage trainId={} pIdx={} from {} to {} ({} player(s))",
            sourceProvider.getTrainId(), sourceProvider.getPIdx(),
            sourceLevel.dimension().location(), job.targetDim.location(),
            passengers.size());

        return true;
    }
}
