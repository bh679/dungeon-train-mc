package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a sampled mob can actually stand in the room it was sampled into.
 *
 * <p>A chunk dimension's mobs are spawned at the positions the sample generated them at, but the
 * room is not quite the sample: its frame may build into the room box, the corridors keep their own
 * blocks, and rows the cube does not reach keep the template's. A mob whose box lands in any of those
 * would spawn stuck in a wall. This moves such a mob to the nearest spot its own box fits, inside the
 * room, and leaves every mob that already fits exactly where the sample put it — a fish in the water,
 * a bat in the air, a ghast over the lava are fitting, not blocked.</p>
 */
final class PortalRoomMobFit {

    /** How far sideways a blocked mob may be moved to find room. */
    static final int MAX_RING = 3;

    /** What became of one mob. */
    enum Outcome { KEPT, MOVED, BLOCKED }

    private PortalRoomMobFit() {}

    /**
     * Fit {@code mob} into the room at {@code origin} of {@code size}, moving it if its box is blocked.
     *
     * @return {@link Outcome#BLOCKED} when nowhere nearby fits — the caller should not spawn it
     */
    static Outcome fit(ServerLevel level, Mob mob, BlockPos origin, Vec3i size, PortalCorridorMask mask) {
        if (fits(level, mob, mob.getBoundingBox(), mask)) return Outcome.KEPT;
        Vec3 at = mob.position();
        BlockPos cell = BlockPos.containing(at);
        // Read off the world, not the mob: one not yet added has never ticked, so it knows nothing
        // about the water it stands in.
        boolean wantsGround = !mob.isNoGravity()
            && !(mob instanceof net.minecraft.world.entity.FlyingMob)
            && !(mob instanceof net.minecraft.world.entity.ambient.AmbientCreature)
            && level.getFluidState(cell).isEmpty();
        Vec3 fallback = null;
        for (Vec3i offset : candidates(cell.getX() - origin.getX(), cell.getY() - origin.getY(),
                cell.getZ() - origin.getZ(), size, MAX_RING)) {
            Vec3 to = at.add(offset.getX(), offset.getY(), offset.getZ());
            AABB box = mob.getBoundingBox().move(offset.getX(), offset.getY(), offset.getZ());
            if (!insideRoom(box, origin, size) || !fits(level, mob, box, mask)) continue;
            if (!wantsGround || standsOnSomething(level, box)) {
                mob.moveTo(to.x, to.y, to.z, mob.getYRot(), mob.getXRot());
                return Outcome.MOVED;
            }
            if (fallback == null) fallback = to;
        }
        if (fallback == null) return Outcome.BLOCKED;
        mob.moveTo(fallback.x, fallback.y, fallback.z, mob.getYRot(), mob.getXRot());
        return Outcome.MOVED;
    }

    /**
     * Offsets to try from room-local cell {@code (x, y, z)}, nearest first: straight up the column,
     * then straight down, then rings of growing radius at each height — every one landing on a cell
     * strictly inside the room box (above its floor row, below its roof row).
     */
    static List<Vec3i> candidates(int x, int y, int z, Vec3i size, int maxRing) {
        List<Vec3i> out = new ArrayList<>();
        int top = size.getY() - 2;
        for (int dy = 1; y + dy <= top; dy++) addIfInside(out, x, y, z, 0, dy, 0, size);
        for (int dy = -1; y + dy >= 1; dy--) addIfInside(out, x, y, z, 0, dy, 0, size);
        for (int ring = 1; ring <= maxRing; ring++) {
            for (int dy : verticalOrder(y, top)) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        addIfInside(out, x, y, z, dx, dy, dz, size);
                    }
                }
            }
        }
        return out;
    }

    /** Height offsets from {@code y}, nearest first, up before down, within rows 1..top. */
    private static List<Integer> verticalOrder(int y, int top) {
        List<Integer> out = new ArrayList<>();
        out.add(0);
        for (int d = 1; y + d <= top || y - d >= 1; d++) {
            if (y + d <= top) out.add(d);
            if (y - d >= 1) out.add(-d);
        }
        return out;
    }

    private static void addIfInside(List<Vec3i> out, int x, int y, int z, int dx, int dy, int dz, Vec3i size) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;
        if (nx < 0 || nz < 0 || nx >= size.getX() || nz >= size.getZ()) return;
        if (ny < 1 || ny > size.getY() - 2) return;
        out.add(new Vec3i(dx, dy, dz));
    }

    private static boolean fits(ServerLevel level, Mob mob, AABB box, PortalCorridorMask mask) {
        return level.noCollision(mob, box) && !touchesMask(box, mask);
    }

    private static boolean touchesMask(AABB box, PortalCorridorMask mask) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0E-7); x++) {
            for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY - 1.0E-7); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0E-7); z++) {
                    if (mask.covers(cursor.set(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    private static boolean insideRoom(AABB box, BlockPos origin, Vec3i size) {
        return box.minX >= origin.getX() && box.maxX <= origin.getX() + size.getX()
            && box.minZ >= origin.getZ() && box.maxZ <= origin.getZ() + size.getZ()
            && box.minY >= origin.getY() + 1 && box.maxY <= origin.getY() + size.getY() - 1;
    }

    /** Whether anything solid is right under {@code box}'s feet. */
    private static boolean standsOnSomething(ServerLevel level, AABB box) {
        AABB below = new AABB(box.minX, box.minY - 0.0625, box.minZ, box.maxX, box.minY, box.maxZ);
        return !level.noCollision(below);
    }
}
