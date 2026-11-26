package games.brennan.dungeontrain.perks.effects.hoarder;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hoarder tier 2 perk "Chest Sense": every {@value #COOLDOWN_MINUTES} minutes the player senses the
 * chests, barrels and vases around them through walls for a few seconds. The pulse timing lives in
 * {@link ChestSenseEvents}; the outline is client side ({@code client/ChestSenseRenderer}).
 *
 * <p>No caps: range grows two blocks a point and the glow a second a point.</p>
 *
 * <p>Carriage containers live in a Sable plot, not at their world position, so {@link #scan} also
 * walks each sub-level near the player and reports those positions plot-local with the sub-level's
 * id; the client places them with the sub-level's render pose so they ride a moving train.</p>
 */
public final class ChestSense {

    public static final String ID = "chest-sense";

    static final double BASE_RANGE = 4.0;
    static final double RANGE_PER_RANK = 2.0;
    static final int BASE_SECONDS = 5;
    static final int SECONDS_PER_RANK = 1;
    static final int COOLDOWN_MINUTES = 5;

    /** A sensed container: {@code subLevel} null for one in the world, else {@code pos} is plot-local. */
    public record Sensed(UUID subLevel, BlockPos pos) {}

    private ChestSense() {}

    /** Marks Chest Sense built and registers its chips. */
    static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "range", r -> PerkStats.num(range(Math.max(1, r)), Unit.BLOCKS));
        PerkStats.register(ID, "duration", r -> PerkStats.num(glowSeconds(Math.max(1, r)), Unit.SECONDS));
        PerkStats.register(ID, "cooldown", r -> PerkStats.num(COOLDOWN_MINUTES, Unit.MINUTES));
    }

    /** Sense radius in blocks; 0 when inactive. */
    public static double range(int rank) {
        if (rank <= 0) return 0.0;
        return BASE_RANGE + RANGE_PER_RANK * rank;
    }

    /** Seconds the outlines last; 0 when inactive. */
    public static int glowSeconds(int rank) {
        if (rank <= 0) return 0;
        return BASE_SECONDS + SECONDS_PER_RANK * rank;
    }

    static long cooldownTicks() {
        return COOLDOWN_MINUTES * 60L * 20L;
    }

    /** Chests, trapped chests, barrels and vases. Ender chests are not chests here. */
    static boolean isSensed(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof DecoratedPotBlockEntity;
    }

    /** Every sensed container within {@code range} of {@code centre}, in the world and on carriages. */
    static List<Sensed> scan(ServerLevel level, Vec3 centre, double range) {
        List<Sensed> found = new ArrayList<>();
        double rangeSq = range * range;
        int minCx = (int) Math.floor(centre.x - range) >> 4;
        int maxCx = (int) Math.floor(centre.x + range) >> 4;
        int minCz = (int) Math.floor(centre.z - range) >> 4;
        int maxCz = (int) Math.floor(centre.z + range) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (isSensed(be) && be.getBlockPos().getCenter().distanceToSqr(centre) <= rangeSq) {
                        found.add(new Sensed(null, be.getBlockPos()));
                    }
                }
            }
        }
        BoundingBox3d box = new BoundingBox3d(centre.x - range, centre.y - range, centre.z - range,
            centre.x + range, centre.y + range, centre.z + range);
        Vector3d world = new Vector3d();
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, box)) {
            if (sub.isRemoved()) continue;
            LevelPlot plot = sub.getPlot();
            if (plot == null) continue;
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!isSensed(be)) continue;
                    Vec3 local = be.getBlockPos().getCenter();
                    sub.logicalPose().transformPosition(new Vector3d(local.x, local.y, local.z), world);
                    if (centre.distanceToSqr(world.x, world.y, world.z) <= rangeSq) {
                        found.add(new Sensed(sub.getUniqueId(), be.getBlockPos()));
                    }
                }
            }
        }
        return found;
    }
}
