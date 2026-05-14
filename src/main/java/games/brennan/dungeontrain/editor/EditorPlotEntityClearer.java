package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Shared editor-flow helper: discard every non-player entity whose bounding
 * box overlaps the AABB defined by {@code [origin, origin + size)}.
 *
 * <p>Mirrors the private {@code discardEntitiesIn} on
 * {@link games.brennan.dungeontrain.train.CarriageContentsPlacer} (same AABB
 * construction, same player-exclusion predicate, same {@code discard()}
 * call). Lifted out so the four non-contents plot types
 * (carriage / pillar / track / tunnel) can mirror the contents-plot reset
 * behavior without reaching into a private helper in {@code train.}</p>
 *
 * <p>Players are spared so an author standing in the plot at reset time
 * doesn't get kicked out of existence.</p>
 */
public final class EditorPlotEntityClearer {

    private EditorPlotEntityClearer() {}

    public static void discardNonPlayersIn(ServerLevel level, BlockPos origin, Vec3i size) {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        AABB box = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX(),
            origin.getY() + size.getY(),
            origin.getZ() + size.getZ()
        );
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player));
        for (Entity e : entities) {
            e.discard();
        }
    }
}
