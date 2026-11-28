package games.brennan.dungeontrain.perks.effects.hoarder;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.perks.BuiltPerks;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkStats.Unit;
import games.brennan.dungeontrain.editor.ContainerContentsRoller;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hoarder tier 2 perk "Chest Sense" (Brennan, 2026-10-29): on a timer, the player senses every
 * <b>unopened, non-empty</b> container within range through walls for {@value #GLOW_SECONDS} s, with a
 * sound and a ring of particles out to the range. Containers: chests, trapped chests, barrels, shulker
 * boxes and vases, on the ground and on carriages. Containers anyone has opened ({@link #OPENED_KEY},
 * stamped by {@link ChestSenseEvents}) and empty ones do not glow. The pulse timing lives in
 * {@link ChestSenseEvents}; the outline and ring are client side ({@code client/ChestSenseRenderer}).
 *
 * <p>Ladder: range 5 blocks at 1 point, +1 a point, uncapped. Interval (2026-11-28) 180 s at 1 point,
 * 40 s at 10, 10 s at 40, linear between and rounded to whole seconds, 10 s from then on. From 35 points
 * the pulse comes as often as the glow lasts, so the glow never goes out.</p>
 *
 * <p>Carriage containers live in a Sable plot, not at their world position, so {@link #scan} also
 * walks each sub-level near the player and reports those positions plot-local with the sub-level's
 * id; the client places them with the sub-level's render pose so they ride a moving train.</p>
 */
public final class ChestSense {

    public static final String ID = "chest-sense";

    static final int GLOW_SECONDS = 15;
    /** Interval anchors (Brennan, 2026-11-28): points → seconds, linear between, floor past the last. */
    private static final int[] ANCHOR_POINTS = {1, 10, 40};
    private static final double[] ANCHOR_SECONDS = {180, 40, 10};
    private static final double FLOOR_SECONDS = 10;

    /** Stamped on a container block entity's persistent data the first time a player opens it. */
    public static final String OPENED_KEY = "dungeontrain:opened";

    /** A sensed container: {@code subLevel} null for one in the world, else {@code pos} is plot-local. */
    public record Sensed(UUID subLevel, BlockPos pos) {}

    private ChestSense() {}

    /** Marks Chest Sense built and registers its chips. */
    static void declare() {
        BuiltPerks.markBuilt(ID);
        PerkStats.register(ID, "range", r -> PerkStats.num(range(Math.max(1, r)), Unit.BLOCKS));
        PerkStats.register(ID, "every", r -> PerkStats.num(everySeconds(Math.max(1, r)), Unit.SECONDS));
    }

    /** Sense radius in blocks: 5 at 1 point, +1 a point; 0 when inactive. */
    public static double range(int rank) {
        if (rank <= 0) return 0.0;
        return 4.0 + rank;
    }

    /**
     * Whole seconds between pulses: 180 at 1 point, 40 at 10, 10 at 40, linear between the anchors
     * and rounded, then 10 from there on; 0 when inactive.
     */
    public static double everySeconds(int rank) {
        if (rank <= 0) return 0.0;
        for (int i = 1; i < ANCHOR_POINTS.length; i++) {
            if (rank <= ANCHOR_POINTS[i]) {
                int from = ANCHOR_POINTS[i - 1];
                double t = (double) (rank - from) / (ANCHOR_POINTS[i] - from);
                double seconds = ANCHOR_SECONDS[i - 1] + t * (ANCHOR_SECONDS[i] - ANCHOR_SECONDS[i - 1]);
                return Math.max(FLOOR_SECONDS, Math.round(seconds));
            }
        }
        return FLOOR_SECONDS;
    }

    static long intervalTicks(int rank) {
        return Math.round(everySeconds(rank) * 20.0);
    }

    static int glowTicks() {
        return GLOW_SECONDS * 20;
    }

    /**
     * Chests (trapped too), barrels, shulker boxes and vases (vanilla's and Deeper Darker's gloomslate pot).
     * Ender chests are not chests here.
     */
    static boolean isSensedKind(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
            || be instanceof ShulkerBoxBlockEntity || isVase(be);
    }

    static boolean isVase(BlockEntity be) {
        return be instanceof DecoratedPotBlockEntity || ContainerContentsRoller.isGloomslatePot(be.getBlockState());
    }

    /** True for a container kind Chest Sense counts that no one has opened and that holds something. */
    static boolean isSensed(BlockEntity be) {
        if (!isSensedKind(be) || be.getPersistentData().getBoolean(OPENED_KEY)) return false;
        if (be instanceof DecoratedPotBlockEntity pot) return pot.getLootTable() != null || !pot.getTheItem().isEmpty();
        if (isVase(be)) return be instanceof Container c && !c.isEmpty();
        if (be instanceof RandomizableContainerBlockEntity c) return c.getLootTable() != null || !c.isEmpty();
        return false;
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
