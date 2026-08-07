package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import net.minecraft.core.BlockPos;
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
 * stamped, {@link #reapTile} when it retires. Keeping them together is the point: they are one
 * invariant, and splitting them across two files is how the second one gets forgotten.</p>
 *
 * <p><b>Retiring reaps; relocating carries.</b> {@code PortalClear.isLoose} deliberately spares mobs,
 * because a structure that moves should take its occupants with it. That is a different event from a
 * copy falling out of the window, which should leave nothing behind — hence a separate reap here
 * rather than a widened {@code isLoose}.</p>
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
    /** Which copy of the room placed it, so a retiring copy reaps its own and no one else's. */
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
        return true;
    }

    /**
     * Take away every mob a retiring copy placed.
     *
     * <p>Scoped by the copy's box <b>and</b> by the marks, not by either alone. Box alone would take a
     * neighbouring copy's mob that had wandered across; marks alone would need a whole-level scan.</p>
     *
     * @return how many were removed
     */
    public static int reapTile(ServerLevel level, BoundingBox box, int pairKey,
                               PortalRoomTiling.Tile tile) {
        List<Entity> doomed = level.getEntities((Entity) null, AABB.of(box),
            e -> belongsTo(e, pairKey, tile));
        for (Entity entity : doomed) {
            entity.discard();
        }
        if (!doomed.isEmpty()) {
            LOGGER.debug("[DungeonTrain] Portal pair {} copy {},{} retired — reaped {} authored mobs",
                pairKey, tile.x(), tile.z(), doomed.size());
        }
        return doomed.size();
    }

    /** How many authored mobs this structure currently has standing, across every copy. */
    public static int liveCount(ServerLevel level, BoundingBox structureBox, int pairKey) {
        return level.getEntities((Entity) null, AABB.of(structureBox),
            e -> markedPair(e) == pairKey).size();
    }

    /** True when {@code entity} was placed by this pair's room, in this copy of it. */
    private static boolean belongsTo(Entity entity, int pairKey, PortalRoomTiling.Tile tile) {
        return !(entity instanceof Player) && marks(entity.getPersistentData(), pairKey, tile);
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

    /** True when {@code data} was marked by this pair's copy {@code tile} and no other. */
    static boolean marks(CompoundTag data, int pairKey, PortalRoomTiling.Tile tile) {
        return data.contains(NBT_PAIR)
            && data.getInt(NBT_PAIR) == pairKey
            && data.getInt(NBT_TILE_X) == tile.x()
            && data.getInt(NBT_TILE_Z) == tile.z();
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
