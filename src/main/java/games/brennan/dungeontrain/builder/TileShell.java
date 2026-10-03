package games.brennan.dungeontrain.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The outside of a template: the cells you could touch from the air around it.
 *
 * <p>A tile preview is a picture a hundred pixels wide, and a tower's interior — every floor, every
 * partition — is tens of thousands of blocks nobody will see in it. For a template too big to bake whole,
 * the preview bakes this instead: flood the empty space from outside the bounding box and keep each cell
 * that flood reaches a face of. A sealed building comes down to its skin; one with a hole in its wall shows
 * what the hole lets you see, which is right too.</p>
 *
 * <p>Pure — no client classes — so it can be tested.</p>
 */
public final class TileShell {

    private TileShell() {}

    /** The cells of {@code cells} with at least one face on air reachable from outside their bounding box. */
    public static <T> Map<BlockPos, T> of(Map<BlockPos, T> cells) {
        if (cells.isEmpty()) return cells;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells.keySet()) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        // One block of margin on every side, so the flood can walk all the way round.
        minX--; minY--; minZ--; maxX++; maxY++; maxZ++;

        Set<BlockPos> outside = new HashSet<>();
        Map<BlockPos, T> shell = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = new BlockPos(minX, minY, minZ);
        outside.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos next = at.relative(d);
                if (next.getX() < minX || next.getX() > maxX || next.getY() < minY || next.getY() > maxY
                    || next.getZ() < minZ || next.getZ() > maxZ) continue;
                T cell = cells.get(next);
                if (cell != null) {
                    shell.put(next, cell);
                } else if (outside.add(next)) {
                    queue.add(next);
                }
            }
        }
        return shell;
    }
}
