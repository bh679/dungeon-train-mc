package games.brennan.dungeontrain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Which empty-cell markers ({@link VariantPlaceholderBlock}) each entity is passing through, so a
 * Half solid marker stays air until the entity has fully left it.
 *
 * <p>Without this, the marker's "solid from above" test is stateless: jump while standing inside
 * one and your feet clear its top, so it turns solid under you and you land on it. Remembering the
 * cell keeps it air for as long as the entity's box still overlaps the cell's column; stepping out
 * sideways forgets it, and the marker is a floor again.</p>
 *
 * <p>Weak keys, so a removed entity's entry goes with it. Client and server each hold their own
 * player object, so client prediction and the server's movement check remember the same cells.
 * Synchronized because both threads ask in single player.</p>
 */
final class PlaceholderPassThrough {

    private static final Map<Entity, Set<BlockPos>> INSIDE = Collections.synchronizedMap(new WeakHashMap<>());

    private PlaceholderPassThrough() {}

    /**
     * Whether {@code entity} is in, or still passing through, the marker at {@code pos}. Records
     * the cell while the entity's box overlaps it, and forgets cells whose column it has left.
     */
    static boolean isPassingThrough(Entity entity, BlockPos pos) {
        AABB box = entity.getBoundingBox();
        synchronized (INSIDE) {
            Set<BlockPos> cells = INSIDE.get(entity);
            if (cells != null) {
                cells.removeIf(cell -> !overlapsColumn(box, cell));
                if (cells.isEmpty()) INSIDE.remove(entity);
            }
            if (box.intersects(new AABB(pos))) {
                INSIDE.computeIfAbsent(entity, e -> new HashSet<>()).add(pos.immutable());
                return true;
            }
            return cells != null && cells.contains(pos);
        }
    }

    /** Whether {@code box} overlaps the vertical column of {@code cell}, at any height. */
    private static boolean overlapsColumn(AABB box, BlockPos cell) {
        return box.maxX > cell.getX() && box.minX < cell.getX() + 1
            && box.maxZ > cell.getZ() && box.minZ < cell.getZ() + 1;
    }
}
