package games.brennan.dungeontrain.compat;

import com.blackgear.vanillabackport.core.data.tags.ModBlockTags;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where a creaking heart's Creaking appears: somewhere in the heart's own space.
 *
 * <p>VanillaBackport's search ({@code SpawnExtras.trySpawnMob}) picks a column up to 16 blocks
 * out, starts 8 blocks above the heart and scans down to the first solid top. In a forest that is
 * the ground; inside a carriage it is the <b>roof</b>, and 16 blocks out is the next carriage or the
 * test basement. So instead this floods out from the heart through open cells and the heart's own
 * tree (its holder logs and leaves), stopped by walls, floor and roof, and spawns on a floor cell
 * it reached. In plot space on a train that is the carriage's own blocks; Sable then moves the new
 * Creaking to the carriage's world position, as before.</p>
 */
public final class CreakingHeartSpawnSpot {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** How far (in blocks, each axis) the flood reaches from the heart. */
    private static final int RADIUS = 8;
    /** Hard cap on cells visited, whatever the shape. */
    private static final int MAX_CELLS = 1024;

    private CreakingHeartSpawnSpot() {}

    /** Spawns one {@code type} for the heart at {@code heart}, or empty when its space has no room. */
    public static <T extends Mob> Optional<T> trySpawn(EntityType<T> type, MobSpawnType spawnType,
                                                       ServerLevel level, BlockPos heart,
                                                       @Nullable BoundingBox room) {
        List<BlockPos> spots = new ArrayList<>(candidates(level, heart, room));
        Collections.shuffle(spots, new java.util.Random(level.random.nextLong()));
        for (BlockPos spot : spots) {
            if (!level.noCollision(type.getSpawnAABB(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5))) continue;
            T mob = type.create(level, null, spot, spawnType, false, false);
            if (mob == null) continue;
            if (mob.checkSpawnRules(level, spawnType) && mob.checkSpawnObstruction(level)) {
                level.addFreshEntityWithPassengers(mob);
                mob.playAmbientSound();
                LOGGER.debug("[DungeonTrain] creaking heart {} spawned {} at {} ({} spots, room {})",
                    heart.toShortString(), type.getDescriptionId(), spot.toShortString(), spots.size(), room);
                return Optional.of(mob);
            }
            mob.discard();
        }
        LOGGER.debug("[DungeonTrain] creaking heart {} found no spawn spot ({} spots)",
            heart.toShortString(), spots.size());
        return Optional.empty();
    }

    /**
     * Floor cells reachable from the heart without passing through a wall, floor or roof — and,
     * when the heart's {@code room} is known, inside it with head room for the whole Creaking.
     */
    static List<BlockPos> candidates(ServerLevel level, BlockPos heart, @Nullable BoundingBox room) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(heart);
        queue.add(heart);
        while (!queue.isEmpty() && seen.size() < MAX_CELLS) {
            BlockPos cell = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = cell.relative(d);
                if (!withinRadius(heart, next) || (room != null && !room.isInside(next)) || !seen.add(next)) continue;
                BlockState state = level.getBlockState(next);
                boolean open = state.getCollisionShape(level, next).isEmpty();
                if (!open && !isTree(state)) continue;
                queue.add(next);
                if (open && standsOn(level, next.below()) && fits(room, next)) out.add(next);
            }
        }
        return out;
    }

    /** Whether a Creaking standing at {@code feet} stays inside {@code room} (about 2.7 tall: three cells). */
    static boolean fits(@Nullable BoundingBox room, BlockPos feet) {
        return room == null || (room.isInside(feet) && feet.getY() + 2 <= room.maxY());
    }

    private static boolean withinRadius(BlockPos heart, BlockPos p) {
        return Math.abs(p.getX() - heart.getX()) <= RADIUS
            && Math.abs(p.getY() - heart.getY()) <= RADIUS
            && Math.abs(p.getZ() - heart.getZ()) <= RADIUS;
    }

    /** The heart's own tree: its holder logs and leaves, which the flood may pass through. */
    private static boolean isTree(BlockState state) {
        return state.is(ModBlockTags.CREAKING_HEART_HOLDERS) || state.is(BlockTags.LEAVES);
    }

    /** VanillaBackport's floor rule: a full-topped block that is not leaves. */
    private static boolean standsOn(ServerLevel level, BlockPos below) {
        BlockState floor = level.getBlockState(below);
        return !floor.is(BlockTags.LEAVES) && Block.isFaceFull(floor.getCollisionShape(level, below), Direction.UP);
    }
}
