package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.List;

/**
 * The mobs a portal room's variant sidecar asks for — spawning them, and taking them away again.
 *
 * <p>Room mobs used to be dropped with a warning, and the reason was sound: a portal room
 * <b>repeats</b>. The tiling window is Chebyshev radius 5 — 121 copies — and it has no memory, so
 * walking back over ground you left re-stamps it. Spawning per stamp with nothing removing them again
 * is not a burst but a leak: every mob is {@code setPersistenceRequired()}, so none ever despawn. A
 * room with two mob cells at a one-in-eleven roll works out around two hundred of them per hundred
 * rooms walked, climbing for as long as anyone keeps walking.</p>
 *
 * <p>So spawning is only half of it, and this class owns both halves — {@link #spawn} when a copy is
 * stamped, {@link #sweepVolume} when it retires. Keeping them together is the point: they are one
 * invariant, and splitting them across two files is how the second one gets forgotten.</p>
 *
 * <p><b>Retiring sweeps; relocating carries.</b> {@code PortalClear.isLoose} deliberately spares
 * mobs, because a structure that moves should take its occupants with it — {@link #reapPair} then
 * takes only what the room itself authored, so the villager or pet a player led in is carried across.
 * A copy falling out of the window is the other event entirely: its floor is deleted and nothing is
 * built to replace it, so it should leave nothing behind at all. Hence a separate sweep here rather
 * than a widened {@code isLoose}, and hence the two having different rules about what they take.</p>
 */
public final class PortalRoomMobs {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Most authored mobs one portal structure may have alive at once.
     *
     * <p>The window bounds this on its own for a sanely-weighted room — a full 121 copies of a room
     * with two one-in-eleven cells is about twenty mobs. This is the backstop for a room that is not
     * sanely weighted: a cell at weight 1 against nothing else spawns in <i>every</i> copy. Refusals
     * are logged rather than silent, because a room quietly holding fewer mobs than it was authored
     * with looks like the spawn is broken again.</p>
     */
    public static final int MAX_LIVE_PER_STRUCTURE = 64;

    /** Which portal pair placed this mob — the pair's entry carriage index. */
    private static final String NBT_PAIR = "DungeonTrainPortalPair";
    /**
     * Which copy of the room placed it. Provenance rather than a rule — a retiring copy sweeps its
     * whole volume now ({@link #sweepVolume}) — but the copy that spawned a mob is the first thing
     * worth knowing when one turns up somewhere it should not have, and it is one {@code /data get
     * entity} away.
     */
    private static final String NBT_TILE_X = "DungeonTrainPortalTileX";
    private static final String NBT_TILE_Z = "DungeonTrainPortalTileZ";

    private PortalRoomMobs() {}

    /**
     * Put one authored mob into a copy of the room.
     *
     * <p>The spawn itself is {@link CarriageContentsPlacer#spawnVariantMob}, unchanged — it already
     * builds the entity from the cell's NBT, gives it a fresh UUID, applies DT's contents tag, rolls
     * a slime's size, calls {@code finalizeSpawn} and makes it persistent. Two things follow from
     * reusing it rather than writing a second spawner:</p>
     *
     * <ul>
     *   <li>it spawns as {@code MobSpawnType.SPAWN_EGG}, which {@link PortalRoomSpawnGuard} does not
     *       cancel — the guard's rule is "nothing arrives here on its own", and this did not;</li>
     *   <li>the mob carries {@code contentsTagFor(pairKey)}, so everything that already asks "is this
     *       one of ours" answers yes — the train's runway sweep spares it, difficulty scales it
     *       against that point on the track, and {@code clearIntruders} leaves it standing.</li>
     * </ul>
     *
     * <p>{@code pairKey} is the pair's entry carriage index, which is what makes the difficulty
     * scaling land somewhere meaningful rather than on an invented index.</p>
     *
     * @return whether a mob was actually placed
     */
    public static boolean spawn(ServerLevel level, BlockPos worldPos, VariantState picked,
                                int pairKey, PortalRoomTiling.Tile tile, long seed, int liveCount) {
        if (picked == null || !picked.isMob()) return false;

        if (!withinCap(liveCount)) {
            LOGGER.info("[DungeonTrain] Portal pair {} already holds {} authored mobs — not spawning"
                    + " '{}' at {}. Lower the mob cells' weights if the room should be this busy.",
                pairKey, liveCount, picked.entityId(), worldPos);
            return false;
        }

        if (!CarriageContentsPlacer.spawnVariantMob(level, worldPos, picked, pairKey, seed)) {
            return false;
        }

        // Mark which copy placed it. The contents tag says "DT put this here"; these say WHICH copy,
        // which is what lets one retiring copy reap its own mobs and leave its neighbour's alone.
        // Persistent data, so a copy retired after a reload still reaps correctly.
        for (Entity spawned : level.getEntities((Entity) null,
                new AABB(worldPos).inflate(1.0), e -> isUnmarkedRoomMob(e, pairKey))) {
            mark(spawned.getPersistentData(), pairKey, tile);
        }
        // Logged on success, not only on refusal. A room's mob cells are weighted rolls — two cells
        // at one-in-eleven means most stamps place nothing — so without this a run that spawned
        // nothing reads exactly like a run where the spawn is broken, which is the state this whole
        // change was fixing. The reap logs at debug; this is the one worth seeing by default.
        LOGGER.info("[DungeonTrain] Portal pair {} copy {},{} spawned '{}' at {}",
            pairKey, tile.x(), tile.z(), picked.entityId(), worldPos);
        return true;
    }

    /**
     * Claim the item frames and paintings a copy's stamp just hung.
     *
     * <p>{@link TemplateDecor} spawns a room's decoration as part of the block stamp and hands back
     * no handle on what it made, so the mark is applied by looking for it — the same shape as
     * {@link #isUnmarkedRoomMob}, and narrow in the same way: only decoration, only inside this
     * copy's box, and only what carries no mark already, so a neighbouring copy's frame that this
     * box happens to touch is never re-claimed.</p>
     *
     * <p>Without this a room's pictures would be spawned once per copy and taken away never — the
     * reap is scoped by the mark, and an unmarked entity is invisible to it.</p>
     *
     * @return how many were marked
     */
    public static int markDecor(ServerLevel level, BlockPos origin, Vec3i size, int pairKey,
                                PortalRoomTiling.Tile tile) {
        AABB box = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX(), origin.getY() + size.getY(), origin.getZ() + size.getZ());
        int marked = 0;
        for (Entity entity : level.getEntities((Entity) null, box, TemplateDecor::isDecor)) {
            if (entity.getPersistentData().contains(NBT_PAIR)) continue;
            mark(entity.getPersistentData(), pairKey, tile);
            marked++;
        }
        return marked;
    }

    /**
     * Take away everything standing in a volume that is about to stop existing — a retiring room copy
     * or a retiring extra corridor.
     *
     * <p><b>The whole volume, not just what this copy placed.</b> The reap used to be scoped by the
     * copy's box <i>and</i> its {@code tile} mark, which left a mob that had walked one copy over
     * matched by neither: not by its birth tile, whose box it had left, and not by the tile it now
     * stood in, whose mark it did not carry. Every authored mob is
     * {@code setPersistenceRequired()}, so each one that slipped through was permanent — it lost its
     * floor with the copy and fell to the world floor to stand there for the life of the world. The
     * same held for anything that got in by another route: a mob led in, a minecart, an armour stand.
     * Retiring a copy deletes its floor either way, so leaving an entity behind only converts it into
     * a falling one.</p>
     *
     * <p><b>Position, not overlap.</b> Tile boxes abut, so an entity standing just inside the copy
     * next door has an AABB that touches this one. The query is by AABB because that is the index the
     * level offers; membership is then decided on the entity's own position, so a sweep takes only
     * what is really in the volume that is going.</p>
     *
     * <p><b>Players are never swept</b>, nor is anything in a stack a player is part of — the horse
     * they are riding, the boat they are in. A copy is only retired once nobody is within the tiling
     * window of it ({@code PortalRoomTiler#tick}), so this should never fire under anyone's feet;
     * it is the guard that keeps a bug there from being a fatal one.</p>
     *
     * @param what a short name for the retiring volume, for the log — the caller knows whether it is
     *             a room copy or a corridor
     * @return how many were removed
     */
    public static int sweepVolume(ServerLevel level, BoundingBox box, int pairKey, String what) {
        List<Entity> doomed = level.getEntities((Entity) null, AABB.of(box),
            entity -> sweepable(entity, box));
        for (Entity entity : doomed) {
            entity.discard();
        }
        if (!doomed.isEmpty()) {
            LOGGER.debug("[DungeonTrain] Portal pair {} {} retired — swept {} entities",
                pairKey, what, doomed.size());
        }
        return doomed.size();
    }

    /** True when a retiring volume should take {@code entity} with it. */
    private static boolean sweepable(Entity entity, BoundingBox box) {
        if (entity.getRootVehicle().getSelfAndPassengers().anyMatch(e -> e instanceof Player)) {
            return false;
        }
        return inside(box, entity.getX(), entity.getY(), entity.getZ());
    }

    /**
     * True when a position lies in {@code box}.
     *
     * <p>Split out and package-private so the seam rule can be tested without a world: two copies of a
     * room share a wall, and a sweep that took what was standing on the far side of it would empty the
     * room the player is walking back into.</p>
     */
    static boolean inside(BoundingBox box, double x, double y, double z) {
        return box.isInside(BlockPos.containing(x, y, z));
    }

    /**
     * Take away every mob this pair's room placed, in any copy of it.
     *
     * <p>{@link #sweepVolume}'s counterpart for a structure that is being <b>relocated</b> rather than
     * shedding one copy. The whole room is about to be erased and stamped again somewhere else, and
     * the stamp rolls a fresh set of authored mobs — so without this the old set is carried to the
     * new site by {@code carryStructureOccupants}, spared by {@code clearIntruders} for carrying DT's
     * contents tag, and left standing beside its own replacement, once per relocation.</p>
     *
     * <p>Scoped by the pair mark, so it takes only what this room authored: a villager or pet a
     * player led into the portal carries no mark and is carried across as before.</p>
     *
     * @return how many were removed
     */
    public static int reapPair(ServerLevel level, BoundingBox box, int pairKey) {
        List<Entity> doomed = level.getEntities((Entity) null, AABB.of(box),
            e -> markedPair(e) == pairKey);
        for (Entity entity : doomed) {
            entity.discard();
        }
        if (!doomed.isEmpty()) {
            LOGGER.debug("[DungeonTrain] Portal pair {} relocating — reaped {} authored mobs", pairKey,
                doomed.size());
        }
        return doomed.size();
    }

    /** How many authored mobs this structure currently has standing, across every copy. */
    public static int liveCount(ServerLevel level, BoundingBox structureBox, int pairKey) {
        return level.getEntities((Entity) null, AABB.of(structureBox),
            e -> markedPair(e) == pairKey).size();
    }

    /** The pair that placed {@code entity}, or {@link Integer#MIN_VALUE} if DT's room did not. */
    private static int markedPair(Entity entity) {
        if (entity instanceof Player) return Integer.MIN_VALUE;
        return markedPair(entity.getPersistentData());
    }

    // ---------- the marks, as plain NBT ----------
    //
    // Split out from the entity so the reap rule can be tested without a world. It is the whole
    // correctness of the reap — a copy must take its own mobs and leave its neighbour's — and that
    // is exactly the kind of off-by-one an in-game pass is bad at noticing.

    /** Record that this pair's copy {@code tile} placed the entity carrying {@code data}. */
    static void mark(CompoundTag data, int pairKey, PortalRoomTiling.Tile tile) {
        data.putInt(NBT_PAIR, pairKey);
        data.putInt(NBT_TILE_X, tile.x());
        data.putInt(NBT_TILE_Z, tile.z());
    }

    /** The pair named in {@code data}, or {@link Integer#MIN_VALUE} if it carries no mark. */
    static int markedPair(CompoundTag data) {
        return data.contains(NBT_PAIR) ? data.getInt(NBT_PAIR) : Integer.MIN_VALUE;
    }

    /** Whether another authored mob may be added to a structure already holding {@code liveCount}. */
    static boolean withinCap(int liveCount) {
        return liveCount < MAX_LIVE_PER_STRUCTURE;
    }

    /**
     * A mob this pair just spawned that has not been marked with its copy yet.
     *
     * <p>Narrow on purpose: {@code spawnVariantMob} gives no handle on what it created, so the mark
     * is applied by looking for it. Requiring the pair's contents tag and the absence of a mark keeps
     * that from touching a mob an earlier copy already claimed.</p>
     */
    private static boolean isUnmarkedRoomMob(Entity entity, int pairKey) {
        if (entity instanceof Player) return false;
        if (entity.getPersistentData().contains(NBT_PAIR)) return false;
        return entity.getTags().contains(CarriageContentsPlacer.contentsTagFor(pairKey));
    }
}
