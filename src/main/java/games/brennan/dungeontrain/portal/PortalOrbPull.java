package games.brennan.dungeontrain.portal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws an experience orb in one corridor towards a player standing in the other.
 *
 * <p>A mob shot through the portal dies in the copy the player is not in, and vanilla's orb only
 * follows a player within eight blocks of it in its own level — the only player is in the mirrored
 * copy, which in world terms is the far side of the map. So the orb sat where it fell until the
 * player crossed to it. This pulls it towards the player's <i>mirrored</i> position instead, with
 * the impulse vanilla's own {@code ExperienceOrb.tick} uses, so it moves the way an orb always
 * moves. It only needs to reach the midpoint: {@link PortalEntityTransit} then carries it into the
 * player's copy, where it is within eight blocks of a real player and vanilla pulls it the rest of
 * the way and collects it. Nothing here collects anything.</p>
 *
 * <p><b>Vanilla first.</b> An orb with a real player within range in its own copy is left to it —
 * two pulls on one orb would double its speed. Items are not pulled: vanilla does not pull those
 * either, and a player picks them up by walking to them as they always have.</p>
 */
public final class PortalOrbPull {

    /** Vanilla's follow range, squared. {@code ExperienceOrb.tick}'s {@code d0 < 64.0}. */
    static final double RANGE_SQ = 64.0;

    private PortalOrbPull() {}

    /**
     * Pull every orb in {@code occupants} that has a mirrored player within range and no real one.
     *
     * @param frames the pair's frames as {@link PortalPuppets#poseAligned} reads them — the
     *               mirrored point is computed through the carriage origin, and the box-derived one
     *               is a tick behind the train
     */
    public static void run(ServerLevel level, List<ServerPlayer> players, PortalFrames frames,
                           List<Entity> occupants) {
        for (Entity entity : occupants) {
            if (!(entity instanceof ExperienceOrb orb)) continue;
            if (!orb.isAlive() || orb.isPassenger()) continue;

            int frame = frames.frameAt(orb.getX(), orb.getY(), orb.getZ());
            if (frame == PortalFrames.FRAME_NONE) continue;

            // Vanilla's own follow takes over the moment a real player is near enough.
            if (level.getNearestPlayer(orb, Math.sqrt(RANGE_SQ)) != null) continue;

            Vec3 target = nearestMirroredPlayer(frames, frame, players, orb.position());
            if (target == null) continue;

            orb.setDeltaMovement(orb.getDeltaMovement().add(impulse(orb.position(), target)));
        }
    }

    /**
     * Where the nearest player who is not in the orb's own corridor would be if they were: the
     * point in the orb's frame with the same offset the player has from the other one. A player in
     * the room beyond a corridor counts — their mirrored point lies past the corridor's far door,
     * which still draws the orb over the midpoint.
     */
    static Vec3 nearestMirroredPlayer(PortalFrames frames, int orbFrame, List<ServerPlayer> players,
                                      Vec3 orb) {
        int other = orbFrame == PortalFrames.FRAME_CARRIAGE ? PortalFrames.FRAME_TWIN
            : PortalFrames.FRAME_CARRIAGE;
        Vec3 best = null;
        double bestSq = RANGE_SQ;
        for (ServerPlayer player : players) {
            if (player.isSpectator() || player.isDeadOrDying()) continue;
            if (frames.frameAt(player.getX(), player.getY(), player.getZ()) == orbFrame) continue;

            PortalFrames.Move m = frames.mirrorFrom(other, player.getX(),
                player.getY() + player.getEyeHeight() / 2.0, player.getZ());
            Vec3 point = new Vec3(m.x(), m.y(), m.z());
            double d = point.distanceToSqr(orb);
            if (d < bestSq) {
                bestSq = d;
                best = point;
            }
        }
        return best;
    }

    /**
     * The velocity to add this tick — {@code ExperienceOrb.tick}'s arithmetic, unchanged, so a
     * mirrored pull is indistinguishable from a real one. Zero outside the range; {@code target}
     * already includes vanilla's half-eye-height lift.
     */
    static Vec3 impulse(Vec3 orb, Vec3 target) {
        Vec3 v = target.subtract(orb);
        double d0 = v.lengthSqr();
        if (d0 >= RANGE_SQ || d0 == 0) return Vec3.ZERO;
        double d1 = 1.0 - Math.sqrt(d0) / 8.0;
        return v.normalize().scale(d1 * d1 * 0.1);
    }
}
