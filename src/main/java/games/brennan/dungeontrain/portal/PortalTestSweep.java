package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Clearing up after a structure that stood alone in a private band — a portal test, a tribute room visit.
 * Shared by {@code PortalTestCommand} and {@code TributeRoomVisitService}, so both sweep the same box.
 */
public final class PortalTestSweep {

    private PortalTestSweep() {}

    /**
     * The footprint and every copy the tiler grew around it, as one box. {@code footprintOf} is
     * deliberately blind to tiling — copies are laid around the corridors rather than through them —
     * so clearing only that box would leave every copy standing under the world.
     */
    public static BoundingBox windowBox(ServerLevel level, PortalStructure structure, CarriageDims dims) {
        BoundingBox footprint = PortalCarriageBuilder.footprintOf(level, structure, dims);
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        return new BoundingBox(
            Math.min(footprint.minX(), structure.tiledMinX(dims, layout) - 1),
            footprint.minY(),
            Math.min(footprint.minZ(), structure.tiledMinZ(dims, layout) - 1),
            Math.max(footprint.maxX(), structure.tiledMaxX(dims, layout) + 1),
            Math.max(footprint.maxY(), structure.origin().getY() + structure.roomSize().getY() + 1),
            Math.max(footprint.maxZ(), structure.tiledMaxZ(dims, layout) + 1));
    }

    /** Remove every entity but a player from {@code box} — nothing else lives in a private band. */
    public static int discardMobs(ServerLevel level, BoundingBox box) {
        int removed = 0;
        for (Entity entity : level.getEntities((Entity) null, AABB.of(box), entity -> !(entity instanceof Player))) {
            entity.discard();
            removed++;
        }
        return removed;
    }
}
