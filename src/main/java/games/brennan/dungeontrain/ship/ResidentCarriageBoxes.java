package games.brennan.dungeontrain.ship;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import org.joml.primitives.AABBdc;

/**
 * The resident carriages' world boxes, snapshotted once per server tick per level.
 *
 * <p>{@link TrainFluidBarrier} is asked "is this block inside a carriage?" for every spread of
 * every fluid tick, thousands of times when a water chunk is promoted. Answering by walking the
 * shipyard each time ({@code findAll()} allocates a list and resolves a weak wrapper per
 * sub-level, and {@code worldAABB()} allocates a box per carriage) was Dungeon Train's own share of
 * the fluid-storm stalls in the 2026-10-28 lag-report sweep. This class does that walk once per
 * tick and hands out a {@link CarriageBoxIndex} of primitive bounds.</p>
 *
 * <p>The snapshot is rebuilt when the level's game time moves on, or when the shipyard's
 * {@link Shipyard#generation() generation} shows a ship was assembled, deleted or reloaded since
 * (so a carriage stamped mid-tick is not missed for the rest of that tick). Ships Sable loads or
 * culls on its own appear next tick, which the margin covers.</p>
 *
 * <p><b>Server thread only.</b> Every reader must check {@code level.getServer().isSameThread()}
 * first and take an uncached path otherwise; the map is unsynchronised on purpose, like the
 * fluid tick that drives it.</p>
 */
public final class ResidentCarriageBoxes {

    /**
     * Blocks each snapshotted box is grown by. A carriage cannot move faster than
     * {@link DungeonTrainConfig#MAX_SPEED} blocks per second, one block per tick, so a point within
     * this margin of a carriage's snapshotted box is within its true box at any later test in the
     * same tick. Doubled for headroom: the cost of a wider margin is one extra live box read for
     * fluid right beside the train, not a wrong answer.
     */
    static final double COARSE_MARGIN_BLOCKS = 2.0 * (DungeonTrainConfig.MAX_SPEED / 20.0);

    /** Per-level snapshot. Weak on the level so an unloaded one does not keep its ships alive. */
    private static final Map<ServerLevel, Snapshot> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    private record Snapshot(long tick, long generation, CarriageBoxIndex<ManagedShip> index) {}

    private ResidentCarriageBoxes() {}

    /**
     * This tick's index of resident carriage boxes for {@code level}, grown by
     * {@link #COARSE_MARGIN_BLOCKS}. A hit in it is a candidate to settle against the ship's live
     * {@link ManagedShip#worldAABB()}; a miss is final.
     */
    public static CarriageBoxIndex<ManagedShip> forTick(ServerLevel level) {
        Shipyard shipyard = Shipyards.of(level);
        long tick = level.getGameTime();
        long generation = shipyard.generation();
        Snapshot cached = CACHE.get(level);
        if (cached != null && cached.tick() == tick && cached.generation() == generation) {
            return cached.index();
        }
        CarriageBoxIndex<ManagedShip> index = build(shipyard);
        CACHE.put(level, new Snapshot(tick, generation, index));
        return index;
    }

    /** Drop every snapshot. Server stop, beside {@link Shipyards#clear()}. */
    public static void clear() {
        CACHE.clear();
    }

    private static CarriageBoxIndex<ManagedShip> build(Shipyard shipyard) {
        List<ManagedShip> resident = new ArrayList<>();
        for (ManagedShip ship : shipyard.findAll()) {
            // A culled wrapper reports a frozen last-known box that would dam fluid where the
            // train no longer is (see project_stale_ghost_aabb_collision).
            if (ship.isResident()) {
                resident.add(ship);
            }
        }
        return CarriageBoxIndex.of(resident, ResidentCarriageBoxes::readBox, COARSE_MARGIN_BLOCKS);
    }

    private static void readBox(ManagedShip ship, double[] out) {
        AABBdc box = ship.worldAABB();
        out[0] = box.minX();
        out[1] = box.minY();
        out[2] = box.minZ();
        out[3] = box.maxX();
        out[4] = box.maxY();
        out[5] = box.maxZ();
    }
}
