package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The free ENTITIES of a shared carriage — armor stands, item frames, paintings, end crystals, the mobs
 * a builder put in a room — captured into the carriage's relay blob and spawned back when another world
 * leases that build. {@link CarriageBlockSnapshot} owns the blocks; this owns everything standing among
 * them, and the two meet only at the blob's {@code ents} list.
 *
 * <h2>Ship space, not world space</h2>
 * A carriage's blocks live in its Sable sub-level's plot chunks, but its entities are ordinary
 * <b>world-space</b> entities that Sable re-anchors onto the moving carriage each tick (the same truth
 * {@link ContentsEntitySnapshot} is built on). Their world position means nothing to another world, so
 * capture converts through {@link ManagedShip#worldToShip} and stores the offset from the carriage's own
 * origin — the exact frame the blocks' {@code p} offsets use, so a card in the 3D preview and a
 * respawned armor stand land at the same spot as the block they stand on.
 *
 * <h2>What is captured</h2>
 * Every non-player entity inside the footprint. Excluded, deliberately:
 * <ul>
 *   <li><b>Players</b>, and any vehicle a player is riding — a shared build must never carry a person.</li>
 *   <li><b>PlayerMob echoes</b> ({@value ContentsDespawnController#PLAYER_MOB_ID}): an echo carries a real
 *       player's name and skin, and shipping one into a stranger's world hands out an identity the
 *       player never shared with them.</li>
 *   <li><b>Bosses</b> — a wither posted into the community pool is a weapon, not a build.</li>
 *   <li><b>Passengers</b> as roots: a passenger is already written inside its vehicle's tag.</li>
 * </ul>
 *
 * <h2>Identity</h2>
 * Unlike {@link ContentsEntitySnapshot} — which discards the originals and therefore keeps their UUIDs —
 * capture here leaves the live entity untouched, so every UUID in the tree is stripped. The copy that
 * respawns in another world is a NEW entity; keeping the id would risk a collision the moment a world
 * leased back a build it had authored, and any leash/owner reference it carried would point at an entity
 * that does not exist there.
 */
public final class CarriageEntitySnapshot {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Entity types never captured, whatever a builder does with them. */
    private static final Set<String> EXCLUDED_TYPES = Set.of(
            ContentsDespawnController.PLAYER_MOB_ID,
            "minecraft:wither",
            "minecraft:ender_dragon");

    /** A single entity's NBT over this many bytes is dropped rather than bloating every lease. */
    private static final int MAX_ENTITY_TAG_BYTES = 64 * 1024;

    /** How far outside the footprint an entity still counts as "in this carriage" (deck edges, doorways). */
    private static final double FOOTPRINT_MARGIN = 0.5;

    private CarriageEntitySnapshot() {}

    /** A capture's outputs: the blob's {@code ents} list, and the authored text it carries for moderation. */
    public record Captured(ListTag ents, String text) {}

    // ---- capture ----

    /**
     * Capture every eligible entity standing in the carriage footprint {@code [origin, origin+dims)} of
     * {@code ship}'s sub-level. Returns an empty list (never null) when the carriage holds none.
     *
     * @param origin      the per-carriage shipyard origin — the same one {@link CarriageBlockSnapshot#capture} uses
     * @param maxEntities hard cap; anything past it is dropped with a warning rather than uploaded
     */
    public static Captured capture(SableManagedShip ship, ServerLevel level, BlockPos origin,
                                   CarriageDims dims, int maxEntities) {
        ListTag out = new ListTag();
        StringBuilder text = new StringBuilder();
        if (maxEntities <= 0) return new Captured(out, "");

        int skippedForCap = 0;
        for (Entity entity : candidates(level, ship)) {
            if (!isCapturable(entity)) continue;
            double[] offset = footprintOffset(ship, origin, dims, entity);
            if (offset == null) continue; // in the group's box, but not in THIS carriage
            if (out.size() >= maxEntities) { skippedForCap++; continue; }
            CompoundTag entry = encodeEntity(entity, offset);
            if (entry == null) continue;
            out.add(entry);
            CarriageTextScan.appendEntity(entity, text);
        }
        if (skippedForCap > 0) {
            LOGGER.warn("[DungeonTrain] shared carriage at {} holds more entities than the cap ({}) — {} not captured.",
                    origin, maxEntities, skippedForCap);
        }
        return new Captured(out, text.toString());
    }

    /** Entities anywhere aboard the ship — the same inflated box {@link ContentsEntitySnapshot} sweeps. */
    private static List<Entity> candidates(ServerLevel level, ManagedShip ship) {
        var b = ship.worldAABB();
        AABB box = new AABB(
                b.minX() - 1, b.minY() - 1, b.minZ() - 1,
                b.maxX() + 1, b.maxY() + 2, b.maxZ() + 1);
        return level.getEntities((Entity) null, box, Entity::isAlive);
    }

    /** Whether this entity may be uploaded at all — see the class javadoc's exclusion list. */
    private static boolean isCapturable(Entity entity) {
        if (entity == null || entity instanceof Player) return false;
        if (entity.isPassenger()) return false; // written inside its vehicle's tag instead
        if (EXCLUDED_TYPES.contains(typeId(entity))) return false;
        for (Entity part : entity.getSelfAndPassengers().toList()) {
            // A boat or minecart a player is sitting in takes the player with it if captured whole.
            if (part instanceof Player) return false;
            if (EXCLUDED_TYPES.contains(typeId(part))) return false;
        }
        return true;
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /**
     * The entity's position as an offset from the carriage origin, in the blocks' own frame, or null when
     * it falls outside this carriage's footprint (a group holds several carriages inside one ship box).
     */
    private static double[] footprintOffset(ManagedShip ship, BlockPos origin, CarriageDims dims, Entity entity) {
        // worldToShip mutates its argument in place, so this must be a fresh vector per entity.
        Vector3d ship_ = ship.worldToShip(new Vector3d(entity.getX(), entity.getY(), entity.getZ()));
        double dx = ship_.x - origin.getX();
        double dy = ship_.y - origin.getY();
        double dz = ship_.z - origin.getZ();
        if (outside(dx, dims.length()) || outside(dy, dims.height()) || outside(dz, dims.width())) return null;
        return new double[]{dx, dy, dz};
    }

    private static boolean outside(double v, int extent) {
        return v < -FOOTPRINT_MARGIN || v >= extent + FOOTPRINT_MARGIN;
    }

    /**
     * One blob entry: {@code { id, p:[dx,dy,dz], y:<yaw>, bb:[w,h], dn?:<custom name>, n:<entity NBT> } }.
     * Returns null when the entity refuses to serialise or its tag is implausibly large.
     *
     * <p>{@code bb} and {@code dn} are redundant with {@code n} on purpose: they are what the web viewer
     * draws a card from, and paying two floats and a string here saves it from having to understand
     * entity NBT at all.</p>
     */
    private static CompoundTag encodeEntity(Entity entity, double[] offset) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.saveAsPassenger(nbt)) return null;
        // Position and motion are re-derived at spawn: a stale world Pos would drop the entity where this
        // world's train happened to be, and a minutes-old velocity makes it lurch on arrival.
        nbt.remove("Pos");
        nbt.remove("Motion");
        stripIds(nbt);
        if (nbt.toString().length() > MAX_ENTITY_TAG_BYTES) {
            LOGGER.debug("[DungeonTrain] shared-carriage entity {} too large to capture — skipping.", typeId(entity));
            return null;
        }

        CompoundTag entry = new CompoundTag();
        entry.putString("id", typeId(entity));
        ListTag pos = new ListTag();
        for (double v : offset) pos.add(DoubleTag.valueOf(v));
        entry.put("p", pos);
        entry.putFloat("y", entity.getYRot());
        ListTag bb = new ListTag();
        bb.add(FloatTag.valueOf(entity.getBbWidth()));
        bb.add(FloatTag.valueOf(entity.getBbHeight()));
        entry.put("bb", bb);
        if (entity.getCustomName() != null) entry.putString("dn", entity.getCustomName().getString());
        entry.put("n", nbt);
        return entry;
    }

    /** Drop every UUID in an entity tree (see the class javadoc's Identity note). */
    private static void stripIds(CompoundTag nbt) {
        nbt.remove("UUID");
        ListTag passengers = nbt.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passengers.size(); i++) stripIds(passengers.getCompound(i));
    }

    // ---- spawn (a leased build's entities, at world coords) ----

    /**
     * Spawn a leased carriage's captured entities at {@code shipyardOrigin} — the carriage's origin in
     * shipyard coordinates, the same origin (and the same coordinate frame) the local contents pass
     * spawns its entities at, so an entry's offset lands it exactly where it stood in the world that
     * built it. Returns how many entities actually spawned.
     *
     * <p><b>Runs deferred, not at placement.</b> The caller is the appender's settle handler, the same
     * point local carriage contents spawn from ({@link PendingContentsEntitySpawn}) — an entity added
     * while the placement tracker is still shifting the group is the known displacement bug.</p>
     *
     * <p>Every spawned entity is tagged {@link CarriagePlacer}-style with
     * {@link CarriageContentsPlacer#contentsTagFor(int)} and its spawn anchor. That tag is load-bearing,
     * not bookkeeping: {@link TrainStaticContentsCarrier} carries item frames, paintings and end crystals
     * with the moving train only if they carry it — untagged, a leased build's decor is left standing on
     * the tracks the moment the train pulls away.</p>
     */
    public static int spawn(ServerLevel level, BlockPos shipyardOrigin, ListTag ents, int carriagePIdx) {
        if (ents == null || ents.isEmpty()) return 0;
        int spawned = 0;
        for (int i = 0; i < ents.size(); i++) {
            CompoundTag entry = ents.getCompound(i);
            try {
                if (spawnOne(level, shipyardOrigin, entry, carriagePIdx)) spawned++;
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] leased-carriage entity {} failed to spawn at pIdx={}: {}",
                        entry.getString("id"), carriagePIdx, e.toString());
            }
        }
        return spawned;
    }

    private static boolean spawnOne(ServerLevel level, BlockPos shipyardOrigin, CompoundTag entry, int carriagePIdx) {
        CompoundTag nbt = entry.getCompound("n").copy();
        if (nbt.isEmpty()) return false;
        ListTag p = entry.getList("p", Tag.TAG_DOUBLE);
        if (p.size() < 3) return false;
        Vec3 at = new Vec3(
                shipyardOrigin.getX() + p.getDouble(0),
                shipyardOrigin.getY() + p.getDouble(1),
                shipyardOrigin.getZ() + p.getDouble(2));

        // Write Pos before loading so the entity is constructed in the destination chunk rather than
        // built somewhere else and moved (the ContentsEntitySnapshot.restore idiom).
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(at.x));
        pos.add(DoubleTag.valueOf(at.y));
        pos.add(DoubleTag.valueOf(at.z));
        nbt.put("Pos", pos);

        Entity entity = EntityType.loadEntityRecursive(nbt, level, e -> e);
        if (entity == null) {
            // An entity type this world does not have (a leased build from a world with more mods) is a
            // gap in the build, not a failure of it — the blocks are already down.
            LOGGER.debug("[DungeonTrain] leased carriage: unknown entity id={} — skipped.", entry.getString("id"));
            return false;
        }
        entity.moveTo(at.x, at.y, at.z, entry.getFloat("y"), 0.0f);
        entity.setDeltaMovement(Vec3.ZERO);
        for (Entity part : entity.getSelfAndPassengers().toList()) tagAsContents(level, part, at, carriagePIdx);

        if (!level.tryAddFreshEntityWithPassengers(entity)) {
            // Capture strips every UUID, so a rejection here means a collision with a re-identified copy;
            // re-roll and retry once, exactly as the contents restore does.
            entity.getSelfAndPassengers().forEach(e -> e.setUUID(UUID.randomUUID()));
            if (!level.tryAddFreshEntityWithPassengers(entity)) {
                LOGGER.warn("[DungeonTrain] leased carriage: level rejected entity id={} at pIdx={} — dropped.",
                        entry.getString("id"), carriagePIdx);
                return false;
            }
        }
        return true;
    }

    /**
     * Tag + anchor a spawned entity so the static-contents carrier moves it with its carriage. The anchor
     * is the entity's OWN spawn position in shipyard coords, written with the same keys and the same
     * DOUBLE type {@link CarriageContentsPlacer} uses — {@link TrainStaticContentsCarrier} reads exactly
     * those, resolves the carriage that owns that coordinate, and re-anchors from it every tick.
     */
    private static void tagAsContents(ServerLevel level, Entity entity, Vec3 at, int carriagePIdx) {
        entity.addTag(CarriageContentsPlacer.contentsTagFor(carriagePIdx));
        CompoundTag data = entity.getPersistentData();
        data.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_X, at.x);
        data.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Y, at.y);
        data.putDouble(CarriageContentsPlacer.NBT_SPAWN_SHIPYARD_Z, at.z);
        data.putLong(CarriageContentsPlacer.NBT_SPAWN_GAME_TICK, level.getGameTime());
        data.putInt(CarriageContentsPlacer.NBT_SPAWN_CARRIAGE_PIDX, carriagePIdx);
        // Never let a leased build's mob wander off through vanilla's distance despawn — it is part of
        // the build, and the build is what another world lent us.
        if (entity instanceof Mob mob) mob.setPersistenceRequired();
    }

    // ---- decor fingerprint (has a builder changed the entities, as opposed to a mob wandering?) ----

    /**
     * The entity types whose position is an authoring decision rather than a mob's own business. Only
     * these count towards the fingerprint below: a villager pacing a carriage must not re-upload the
     * build every sweep, but hanging a new item frame is exactly the edit that has to reach the pool.
     */
    private static final Set<String> DECOR_TYPES = Set.of(
            "minecraft:armor_stand",
            "minecraft:item_frame",
            "minecraft:glow_item_frame",
            "minecraft:painting",
            "minecraft:end_crystal");

    /**
     * A cheap value summarising which decor entities stand where in a carriage. The shared-carriage sweep
     * compares the LIVE value ({@link #liveDecorFingerprint}) against the one last uploaded to notice an
     * entity-only edit — placing an armor stand changes no block, so nothing else would ever queue an
     * upload for it. Positions are rounded to a third of a block, which is finer than any deliberate
     * reposition and coarser than float drift.
     */
    public static long decorFingerprint(ListTag ents) {
        long h = SEED;
        if (ents == null) return h;
        for (int i = 0; i < ents.size(); i++) {
            CompoundTag e = ents.getCompound(i);
            String id = e.getString("id");
            if (!DECOR_TYPES.contains(id)) continue;
            ListTag p = e.getList("p", Tag.TAG_DOUBLE);
            h = mix(h, id, e.getString("dn"),
                    p.size() >= 3 ? new double[]{p.getDouble(0), p.getDouble(1), p.getDouble(2)} : new double[3]);
        }
        return h;
    }

    /**
     * The same fingerprint computed from the LIVE carriage without serialising anything — the sweep runs
     * this every 30s per shared carriage, so it must stay a position read, not a capture.
     */
    public static long liveDecorFingerprint(SableManagedShip ship, ServerLevel level, BlockPos origin,
                                            CarriageDims dims) {
        long h = SEED;
        for (Entity entity : candidates(level, ship)) {
            if (entity == null) continue;
            // Type first, capturability second — the two are AND-ed either way, so the fingerprint is
            // unchanged. The box is full of contents mobs and dropped items on a lived-in carriage, and
            // isCapturable walks each one's passenger list; only the handful of decor entities ever
            // needed that work done.
            String id = typeId(entity);
            if (!DECOR_TYPES.contains(id)) continue;
            if (!isCapturable(entity)) continue;
            double[] offset = footprintOffset(ship, origin, dims, entity);
            if (offset == null) continue;
            String name = entity.getCustomName() == null ? "" : entity.getCustomName().getString();
            h = mix(h, id, name, offset);
        }
        return h;
    }

    private static final long SEED = 1125899906842597L;

    /**
     * Order-independent mixing (XOR of per-entity hashes): the live scan and a captured list enumerate the
     * same entities in different orders, and a fingerprint that disagreed with itself over ordering would
     * re-upload the carriage on every single sweep.
     */
    private static long mix(long acc, String id, String name, double[] pos) {
        long h = id.hashCode();
        h = 31 * h + name.hashCode();
        for (double v : pos) h = 31 * h + Math.round(v * 3.0);
        return acc ^ (h * 0x9E3779B97F4A7C15L);
    }

    /** Every entity id in a captured list, for logs and tests. */
    public static List<String> idsOf(ListTag ents) {
        List<String> ids = new ArrayList<>();
        if (ents == null) return ids;
        for (int i = 0; i < ents.size(); i++) ids.add(ents.getCompound(i).getString("id"));
        return ids;
    }
}
