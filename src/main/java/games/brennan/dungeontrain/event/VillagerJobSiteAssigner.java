package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns the profession of the nearest villager job-site block (within
 * {@link #MAX_JOB_DISTANCE} blocks) to a villager spawned on a train carriage.
 *
 * <p>Vanilla job-site acquisition AI does not work on a Sable carriage: the
 * villager renders on the ship but, like the player, its entity position is in
 * world space, while the carriage blocks live in the ship's {@link LevelPlot}
 * at ship-local coords. {@code AcquirePoi} searches world space around the
 * villager and never finds the workstation. We bridge the two spaces the same
 * way {@link SoulCampfireHealEvents} does: locate the ship by world-AABB
 * containment, transform the villager into ship-local space via
 * {@code worldToShip}, scan the plot's loaded chunks there, then map matched
 * block centres back to world space ({@code shipToWorld}) so the nearest-pick
 * runs in a single frame even if more than one carriage is in range.</p>
 *
 * <p>We read block <em>states</em> off the {@link LevelChunk} palette (not just
 * block entities) because most job blocks — composter, cartography/smithing/
 * fletching table, grindstone, stonecutter, loom, cauldron — are plain blocks
 * with no block entity.</p>
 *
 * <p>Only vanilla job sites are recognised (the 13 in {@link #JOB_BLOCKS}); train
 * content is DungeonTrain's own, so modded workstations are out of scope.</p>
 */
public final class VillagerJobSiteAssigner {

    /** Maximum villager-to-block-centre distance (blocks) to claim a job site. */
    private static final double MAX_JOB_DISTANCE = 5.0;

    /**
     * Half-extent of the cubic block scan around the villager. One block wider
     * than {@link #MAX_JOB_DISTANCE} so no block whose centre is in range is
     * missed at the box corners; the precise cut is the distance filter in
     * {@link NearestPicker#nearestWithin}.
     */
    private static final int SCAN_RADIUS = 6;

    /**
     * Vanilla job-site block -> profession. Initialised on class load, which
     * only happens from the server-side entity-join handler (after registries
     * are populated) — never during a unit test, which touches only
     * {@link NearestPicker}.
     */
    private static final Map<Block, VillagerProfession> JOB_BLOCKS = buildJobBlocks();

    private VillagerJobSiteAssigner() {}

    private static Map<Block, VillagerProfession> buildJobBlocks() {
        Map<Block, VillagerProfession> m = new HashMap<>();
        m.put(Blocks.COMPOSTER, VillagerProfession.FARMER);
        m.put(Blocks.BARREL, VillagerProfession.FISHERMAN);
        m.put(Blocks.LOOM, VillagerProfession.SHEPHERD);
        m.put(Blocks.FLETCHING_TABLE, VillagerProfession.FLETCHER);
        m.put(Blocks.LECTERN, VillagerProfession.LIBRARIAN);
        m.put(Blocks.CARTOGRAPHY_TABLE, VillagerProfession.CARTOGRAPHER);
        m.put(Blocks.BREWING_STAND, VillagerProfession.CLERIC);
        m.put(Blocks.BLAST_FURNACE, VillagerProfession.ARMORER);
        m.put(Blocks.GRINDSTONE, VillagerProfession.WEAPONSMITH);
        m.put(Blocks.SMITHING_TABLE, VillagerProfession.TOOLSMITH);
        m.put(Blocks.SMOKER, VillagerProfession.BUTCHER);
        m.put(Blocks.STONECUTTER, VillagerProfession.MASON);
        // Leatherworker's job-site POI is the cauldron; match all fill states.
        m.put(Blocks.CAULDRON, VillagerProfession.LEATHERWORKER);
        m.put(Blocks.WATER_CAULDRON, VillagerProfession.LEATHERWORKER);
        m.put(Blocks.LAVA_CAULDRON, VillagerProfession.LEATHERWORKER);
        m.put(Blocks.POWDER_SNOW_CAULDRON, VillagerProfession.LEATHERWORKER);
        return Map.copyOf(m);
    }

    /**
     * Scans for the nearest vanilla job-site block within {@link #MAX_JOB_DISTANCE}
     * of {@code villager} and returns its profession, or
     * {@link VillagerProfession#NONE} when none is found or the villager is not on
     * a resolvable Sable ship.
     */
    public static VillagerProfession assignNearestJobSite(Villager villager, ServerLevel level) {
        double vx = villager.getX();
        double vy = villager.getY();
        double vz = villager.getZ();

        // The villager's entity position is in WORLD space (it renders on the
        // ship but is a normal world entity, exactly like the player), so
        // findAt(worldPos) can't resolve the ship and a direct world-space scan
        // finds nothing — the blocks live in the plot at ship-local coords.
        // Mirror SoulCampfireHealEvents: find the carriage by world-AABB
        // containment, transform the villager into ship-local space, scan the
        // plot there, and map matched block centres back to world space so the
        // nearest-pick uses a single (world) frame across every nearby carriage.
        List<NearestPicker.Located<VillagerProfession>> candidates = new ArrayList<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc bb = ship.worldAABB();
            if (vx < bb.minX() - SCAN_RADIUS || vx > bb.maxX() + SCAN_RADIUS) continue;
            if (vy < bb.minY() - SCAN_RADIUS || vy > bb.maxY() + SCAN_RADIUS) continue;
            if (vz < bb.minZ() - SCAN_RADIUS || vz > bb.maxZ() + SCAN_RADIUS) continue;
            if (!(ship instanceof SableManagedShip sableShip)) continue;

            Vector3d localV = new Vector3d(vx, vy, vz);
            ship.worldToShip(localV);
            BlockPos center = BlockPos.containing(localV.x, localV.y, localV.z);

            ServerSubLevel subLevel = sableShip.subLevel();
            LevelPlot plot = subLevel.getPlot();
            Map<Long, LevelChunk> chunks = new HashMap<>();
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk != null) {
                    chunks.put(chunk.getPos().toLong(), chunk);
                }
            }

            BlockPos min = center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS);
            BlockPos max = center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                LevelChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                if (chunk == null) {
                    continue;
                }
                BlockState state = chunk.getBlockState(pos);
                VillagerProfession profession = JOB_BLOCKS.get(state.getBlock());
                if (profession == null) {
                    continue;
                }
                // Map the ship-local block centre back to world space so
                // candidates from multiple carriages share one comparison frame.
                // worldToShip/shipToWorld are rigid, so distances are preserved.
                Vector3d worldCentre = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                ship.shipToWorld(worldCentre);
                candidates.add(new NearestPicker.Located<>(
                    worldCentre.x, worldCentre.y, worldCentre.z, profession));
            }
        }

        VillagerProfession picked = NearestPicker.nearestWithin(vx, vy, vz, MAX_JOB_DISTANCE, candidates);
        return picked != null ? picked : VillagerProfession.NONE;
    }
}
