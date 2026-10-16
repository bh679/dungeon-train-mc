package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds a clear standing spot for a mob on a carriage's interior floor (shipyard coords).
 *
 * <p>DT's PlayerMob spawns ({@link PlayerMobGroupSpawner}, {@link PlayerMobBehindSpawner},
 * {@link DeathNoteEchoSpawner}) used to drop the mob on the interior floor centre unchecked, so a
 * contents template with a table, pillar or chest there spawned it inside a block, where it
 * suffocated. The search starts at that centre and walks outward ring by ring, so an open centre
 * still wins and a blocked one gives the nearest open cell.</p>
 *
 * <p>Each floor cell is tried with feet on the floor, then one block up when the floor cell itself
 * has collision — a carpet, slab or low block the mob can stand on. The box test is the entity
 * type's full bounding box against block collision, the same recipe as
 * {@code NetherMobSpawner#hasRoomFor}, so it also catches a low ceiling or an overhang.</p>
 */
public final class CarriageSpawnSpot {

    private CarriageSpawnSpot() {}

    /**
     * A clear feet position for {@code type} in the carriage whose shipyard origin is
     * {@code carriageOrigin}, nearest the interior floor centre; empty when the whole floor is full.
     * Call only once the carriage's blocks are in place.
     */
    public static Optional<BlockPos> clearFloorIn(ServerLevel level, EntityType<?> type,
                                                  BlockPos carriageOrigin, CarriageDims dims) {
        BlockPos interiorOrigin = carriageOrigin.offset(1, 1, 1);
        Vec3i size = CarriageContentsPlacer.interiorSize(dims);
        return find(interiorOrigin, size,
            feet -> level.noCollision(boxAt(type, feet)),
            cell -> !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty());
    }

    private static AABB boxAt(EntityType<?> type, BlockPos feet) {
        return type.getDimensions().makeBoundingBox(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
    }

    /**
     * The first candidate (in {@link #candidateOrder} from the floor centre) where {@code fits}
     * accepts the feet position: the floor cell itself, else the cell above it when {@code solid}
     * says the floor cell can be stood on.
     *
     * @param fits  true when the mob's body fits with its feet at this (absolute) position
     * @param solid true when this (absolute) cell has collision to stand on
     */
    static Optional<BlockPos> find(BlockPos interiorOrigin, Vec3i size,
                                   Predicate<BlockPos> fits, Predicate<BlockPos> solid) {
        for (BlockPos rel : candidateOrder(size, size.getX() / 2, size.getZ() / 2)) {
            BlockPos floor = interiorOrigin.offset(rel);
            if (fits.test(floor)) return Optional.of(floor);
            BlockPos up = floor.above();
            if (size.getY() > 1 && solid.test(floor) && fits.test(up)) return Optional.of(up);
        }
        return Optional.empty();
    }

    /**
     * Every interior floor cell {@code (x, 0, z)} relative to the interior origin, exactly once:
     * {@code (prefX, prefZ)} first, then outward in square rings (Chebyshev distance), each ring in a
     * fixed x-then-z order. Empty for a zero-size floor.
     */
    static List<BlockPos> candidateOrder(Vec3i size, int prefX, int prefZ) {
        int sx = size.getX();
        int sz = size.getZ();
        List<BlockPos> out = new ArrayList<>(Math.max(0, sx * sz));
        if (sx <= 0 || sz <= 0) return out;
        int maxRing = Math.max(Math.max(prefX, sx - 1 - prefX), Math.max(prefZ, sz - 1 - prefZ));
        for (int r = 0; r <= maxRing; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = prefX + dx;
                    int z = prefZ + dz;
                    if (x < 0 || x >= sx || z < 0 || z >= sz) continue;
                    out.add(new BlockPos(x, 0, z));
                }
            }
        }
        return out;
    }
}
