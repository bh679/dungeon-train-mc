package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One carriage group's contents mobs, frozen to NBT while no player is near enough to see them.
 *
 * <h2>Why not reuse {@link PendingContentsEntitySpawn}</h2>
 * That record carries {@code (shipyardOrigin, variant, dims, config, carriageIndex,
 * groupAnchorWorldX)} and rebuilds the carriage's contents from the generation seed. Handing a
 * swept carriage back to it would <b>re-roll</b> the mobs: fresh villager trades, fresh equipment,
 * a fresh loot state. A player who half-looted a carriage, walked away and came back would find a
 * different carriage. So the two are siblings, not one mechanism — the pending record is
 * "generate this carriage", this one is "put back exactly what was here".
 *
 * <h2>Ship space, not world space</h2>
 * Live contents mobs are ordinary <b>world-space</b> entities that Sable re-anchors onto the moving
 * carriage each tick (see {@link TrainStaticContentsCarrier}'s class javadoc). Their world position
 * is therefore meaningless minutes later, once the train has driven on. Each captured entity stores
 * its position converted through {@link ManagedShip#worldToShip}, and restore converts back through
 * {@link ManagedShip#shipToWorld} against the carriage's <i>current</i> pose. That is what makes a
 * mob reappear in the seat it was in rather than stranded where the carriage used to be.
 *
 * <h2>What survives the round trip</h2>
 * Capture goes through {@link Entity#saveAsPassenger}, i.e. the same serialisation vanilla uses to
 * write an entity into a chunk, so inventories, health, attributes, gear, brains, names, custom
 * data and nested passengers all come back untouched. Only {@code Pos} and {@code Motion} are
 * stripped — position because it is re-derived from ship space, motion because a mob restored with
 * a minutes-old velocity lurches on arrival.
 *
 * <p>Villager trades in particular survive because {@code Tags} is preserved, and with it
 * {@code VillagerTrainSpawnEvents.REROLLED_TAG} — the join handler that would otherwise re-roll
 * offers returns early when that tag is present.</p>
 */
public record ContentsEntitySnapshot(
    UUID subLevelId,
    UUID trainId,
    int anchorPIdx,
    long capturedGameTick,
    List<CompoundTag> entities
) {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Captured position in ship space — 3 doubles. Removed from the tag before the entity loads. */
    static final String KEY_SHIP_POS = "DtDespawnShipPos";
    /** Captured yaw, so a restored mob faces the way it did. Removed before load. */
    static final String KEY_SHIP_YROT = "DtDespawnShipYRot";

    private static final String NBT_VERSION = "DtSnapshotVersion";
    private static final String NBT_SUB_LEVEL = "SubLevel";
    private static final String NBT_TRAIN_ID = "TrainId";
    private static final String NBT_ANCHOR_PIDX = "AnchorPIdx";
    private static final String NBT_TICK = "Tick";
    private static final String NBT_ENTITIES = "Entities";
    private static final int CURRENT_VERSION = 1;

    /**
     * Sweep one group: enumerate its contents mobs, serialise them, discard the live entities, and
     * write the snapshot to disk.
     *
     * <p><b>Ordering matters.</b> The file is written <i>before</i> the caller flips the provider's
     * despawned flag, and the entities are discarded only once serialisation has succeeded. If the
     * write fails nothing is discarded and the sweep is abandoned — the mobs stay live. The failure
     * mode is "this carriage keeps costing tick time", never "these mobs are gone".</p>
     *
     * @return number of root entities captured, or -1 if the sweep was abandoned (nothing changed)
     */
    public static int captureAndDiscard(ServerLevel level, Trains.Carriage carriage) {
        ManagedShip ship = carriage.ship();
        TrainTransformProvider provider = carriage.provider();
        if (!ship.isResident()) return -1;

        List<Mob> candidates = sweepableMobsAboard(level, ship);
        if (candidates.isEmpty()) {
            // Nothing to hold. Still mark the group despawned so it stops being re-scanned every
            // tick; restore on an absent file is a no-op that just clears the flag again.
            provider.setContentsSnapshotSize(0);
            return 0;
        }

        List<CompoundTag> captured = new ArrayList<>(candidates.size());
        List<Entity> roots = new ArrayList<>(candidates.size());
        for (Mob mob : candidates) {
            // A mob riding another swept mob is written as a Passengers entry of its vehicle, so it
            // must not also be captured as a root. A mob riding something we are NOT sweeping (an
            // exempt PlayerMob, a boat a player left) is skipped entirely rather than dismounted —
            // pulling it off its vehicle would be a visible gameplay change for a rare case.
            if (mob.isPassenger()) continue;

            CompoundTag nbt = new CompoundTag();
            if (!mob.saveAsPassenger(nbt)) continue;

            // Position is re-derived from ship space at restore; a stale world Pos would drop the mob
            // where the carriage was minutes ago. Motion goes for the same reason — a mob restored
            // with an old velocity lurches on arrival.
            nbt.remove("Pos");
            nbt.remove("Motion");

            // worldToShip mutates its argument in place, so this must be a fresh vector per entity.
            Vector3d shipPos = ship.worldToShip(new Vector3d(mob.getX(), mob.getY(), mob.getZ()));
            ListTag posTag = new ListTag();
            posTag.add(DoubleTag.valueOf(shipPos.x));
            posTag.add(DoubleTag.valueOf(shipPos.y));
            posTag.add(DoubleTag.valueOf(shipPos.z));
            nbt.put(KEY_SHIP_POS, posTag);
            nbt.putFloat(KEY_SHIP_YROT, mob.getYRot());

            captured.add(nbt);
            roots.add(mob);
        }

        if (captured.isEmpty()) {
            provider.setContentsSnapshotSize(0);
            return 0;
        }

        ContentsEntitySnapshot snapshot = new ContentsEntitySnapshot(
            ship.subLevelId(), provider.getTrainId(), provider.getPIdx(),
            level.getGameTime(), captured);

        if (!ContentsSnapshotStore.write(level, snapshot)) {
            // Write failed — leave every entity exactly where it is.
            return -1;
        }

        for (Entity root : roots) {
            // discard(), not kill(): no drops, no death event, no XP, no death report. This is a
            // move, not a death.
            root.getSelfAndPassengers().toList().forEach(Entity::discard);
        }

        provider.setContentsSnapshotSize(captured.size());
        return captured.size();
    }

    /**
     * Put a group's mobs back. Returns the number restored, or -1 if the restore was deferred and
     * should be retried next tick.
     *
     * <p><b>Deferred while frozen.</b> A soft-frozen carriage stops advancing its pose
     * ({@code SableManagedShip.applyTickOutput} early-returns), so {@link ManagedShip#shipToWorld}
     * would resolve against a stale transform and drop mobs where the carriage used to be. Any
     * carriage close enough to restore is tracked, so the freeze controller — which runs first each
     * tick — unfreezes it eagerly and the pose catches up within a tick or two.</p>
     */
    public static int restore(ServerLevel level, Trains.Carriage carriage) {
        ManagedShip ship = carriage.ship();
        TrainTransformProvider provider = carriage.provider();
        if (!ship.isResident()) return -1;
        if (ship instanceof SableManagedShip sable && PhysicsFreeze.isFrozen(sable.subLevel())) {
            return -1;
        }

        ContentsEntitySnapshot snapshot = ContentsSnapshotStore.read(level, ship.subLevelId());
        if (snapshot == null) {
            // Empty carriage, or a snapshot that could not be read (the store already dropped it).
            // Either way the group is live again.
            provider.setContentsSnapshotSize(0);
            return 0;
        }

        int restored = 0;
        for (CompoundTag stored : snapshot.entities()) {
            CompoundTag nbt = stored.copy();
            Vec3 world = readShipPos(nbt, ship);
            float yRot = nbt.getFloat(KEY_SHIP_YROT);
            nbt.remove(KEY_SHIP_POS);
            nbt.remove(KEY_SHIP_YROT);

            // Write Pos before loading so the entity is constructed in the destination chunk rather
            // than built somewhere else and moved.
            ListTag posTag = new ListTag();
            posTag.add(DoubleTag.valueOf(world.x));
            posTag.add(DoubleTag.valueOf(world.y));
            posTag.add(DoubleTag.valueOf(world.z));
            nbt.put("Pos", posTag);

            Entity entity = EntityType.loadEntityRecursive(nbt, level, e -> e);
            if (entity == null) {
                LOGGER.warn("[DungeonTrain] Contents-despawn restore: could not load entity id={} for pIdx={}",
                    nbt.getString("id"), snapshot.anchorPIdx());
                continue;
            }
            entity.moveTo(world.x, world.y, world.z, yRot, 0.0f);
            entity.setDeltaMovement(Vec3.ZERO);

            if (!level.tryAddFreshEntityWithPassengers(entity)) {
                // Rejected because a UUID in the tree is already loaded. Snapshots keep their
                // original UUIDs (so leash / target / owner references survive), which is safe
                // because capture discards the originals — but a duplicate can still appear if the
                // same world was loaded twice or a chunk resurrected a copy. Re-identify and retry
                // once; a mob with a new UUID is far better than a dropped one.
                entity.getSelfAndPassengers().forEach(e -> e.setUUID(UUID.randomUUID()));
                if (!level.tryAddFreshEntityWithPassengers(entity)) {
                    LOGGER.warn("[DungeonTrain] Contents-despawn restore: level rejected entity id={} for pIdx={} — dropped",
                        nbt.getString("id"), snapshot.anchorPIdx());
                    continue;
                }
            }
            // Belt and braces, matching the spawn path: never let a restored mob become eligible for
            // vanilla's own distance despawn.
            if (entity instanceof Mob mob) mob.setPersistenceRequired();
            restored++;
        }

        ContentsSnapshotStore.delete(level, ship.subLevelId());
        provider.setContentsSnapshotSize(0);
        return restored;
    }

    /**
     * Contents mobs currently aboard {@code ship} that the gate is allowed to sweep.
     *
     * <p>The AABB is the same inflated {@link ManagedShip#worldAABB} box
     * {@code PhysicsFreezeController.hasLiveEntityAboard} uses. Note this is deliberately <b>not</b>
     * {@link CarriageContentsPlacer#discardEntitiesAt}, which queries a shipyard-coordinate box —
     * correct at placement time, before Sable has re-anchored the entities, and wrong for anything
     * live.</p>
     */
    private static List<Mob> sweepableMobsAboard(ServerLevel level, ManagedShip ship) {
        var b = ship.worldAABB();
        AABB box = new AABB(
            b.minX() - 1, b.minY() - 1, b.minZ() - 1,
            b.maxX() + 1, b.maxY() + 2, b.maxZ() + 1);
        return level.getEntitiesOfClass(Mob.class, box, mob -> mob.isAlive()
            && ContentsDespawnController.isSweepable(
                BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(),
                mob.getTags(),
                mob.getPersistentData().getAllKeys()));
    }

    private static Vec3 readShipPos(CompoundTag nbt, ManagedShip ship) {
        ListTag pos = nbt.getList(KEY_SHIP_POS, Tag.TAG_DOUBLE);
        Vector3d world = ship.shipToWorld(
            new Vector3d(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)));
        return new Vec3(world.x, world.y, world.z);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NBT_VERSION, CURRENT_VERSION);
        tag.putUUID(NBT_SUB_LEVEL, subLevelId);
        if (trainId != null) tag.putUUID(NBT_TRAIN_ID, trainId);
        tag.putInt(NBT_ANCHOR_PIDX, anchorPIdx);
        tag.putLong(NBT_TICK, capturedGameTick);
        ListTag list = new ListTag();
        list.addAll(entities);
        tag.put(NBT_ENTITIES, list);
        return tag;
    }

    /**
     * Parse a snapshot file. Throws on a version we do not understand rather than guessing — the
     * store catches it, drops the file and treats the group as having nothing held, which is the
     * safe direction (an empty carriage, not duplicated or mangled mobs).
     */
    public static ContentsEntitySnapshot fromNbt(CompoundTag tag) {
        int version = tag.getInt(NBT_VERSION);
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported contents-despawn snapshot version " + version);
        }
        ListTag list = tag.getList(NBT_ENTITIES, Tag.TAG_COMPOUND);
        List<CompoundTag> entities = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            entities.add(list.getCompound(i));
        }
        return new ContentsEntitySnapshot(
            tag.getUUID(NBT_SUB_LEVEL),
            tag.hasUUID(NBT_TRAIN_ID) ? tag.getUUID(NBT_TRAIN_ID) : null,
            tag.getInt(NBT_ANCHOR_PIDX),
            tag.getLong(NBT_TICK),
            List.copyOf(entities));
    }
}
