package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Lands a swing at a puppet on the entity it stands for, in the other corridor.
 *
 * <p>A puppet is a drawing — no entity on the server, none in the client level — so vanilla's attack
 * path cannot reach it. Giving it the source's real id and letting the ordinary interact packet
 * carry it does not work either: the server reach-checks that packet against the attacker, and the
 * real target is forty-odd blocks below in a different corridor, so it is refused. That check is
 * right to exist; the answer is not to weaken it but to state reach in the geometry that actually
 * applies here.</p>
 *
 * <p><b>Reach is measured through the mirror.</b> The attacker's position mapped into the target's
 * copy against the target's real position — a couple of blocks apart in the room, however far apart
 * they are in the world. That is the distance the player can see, and it is the one they are judged
 * on.</p>
 *
 * <p><b>Nothing is taken on the client's word.</b> The packet carries an entity id and nothing else.
 * The pairing is re-derived from {@link PortalPairIndex}, and a claimed target that is not in the
 * corridor paired with the attacker's own is simply dropped — a forged id reaches nothing the
 * attacker could not already have hit.</p>
 *
 * <p><b>The hit is a real attack.</b> {@code Player.attack} rather than a hand-rolled damage call, so
 * it carries the weapon, enchantments, crits, sweep, durability, exhaustion and the attack-strength
 * cooldown exactly as an ordinary swing does — and PvP being switched off denies it inside
 * {@code Player.hurt} without a gate of ours to get wrong.</p>
 */
public final class PortalPuppetAttack {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Slack on top of the player's own reach, in blocks.
     *
     * <p>The client picked its target a tick or so ago against a puppet drawn from the tick before
     * that, and in a corridor everything is moving — the train under the attacker, the mob in the
     * room. A rejection here reads to the player as a swing that passed straight through, so the
     * margin is generous enough that only a genuinely out-of-range claim is refused.</p>
     */
    private static final double REACH_SLACK = 2.0;

    /**
     * The target of the attack currently being performed, if any.
     *
     * <p>Read by {@code PortalPuppetAttackEvents} to redirect knockback. Server thread only — an
     * attack is begun and finished inside one packet handler, so this is never set across a yield.
     * </p>
     */
    private static LivingEntity inFlightTarget;

    /** Where the attacker appears to be standing, in the target's copy. */
    private static Vec3 inFlightAttackerMirrored;

    private PortalPuppetAttack() {}

    /**
     * A player swung at a puppet of the entity with this id.
     *
     * @return true if the hit landed, for logging
     */
    public static boolean attack(ServerPlayer attacker, int sourceEntityId) {
        Entity target = attacker.serverLevel().getEntity(sourceEntityId);
        if (target == null || !target.isAlive()) return false;
        if (target == attacker) return false;

        PortalPairIndex.Entry pair = pairAcross(attacker, target);
        if (pair == null) {
            LOGGER.debug("[DungeonTrain] Portal puppet attack refused: {} and target {} are not either side of a pair",
                attacker.getName().getString(), sourceEntityId);
            return false;
        }

        PortalFrames.Move mirrored = pair.frames().mirror(attacker.getX(), attacker.getY(), attacker.getZ());
        if (mirrored == null) return false;

        Vec3 from = new Vec3(mirrored.x(), mirrored.y(), mirrored.z());
        double reach = attacker.entityInteractionRange() + REACH_SLACK;
        if (from.distanceToSqr(target.position()) > reach * reach) {
            LOGGER.debug("[DungeonTrain] Portal puppet attack refused: {} is out of reach of target {} through the mirror",
                attacker.getName().getString(), sourceEntityId);
            return false;
        }

        // Knockback is the one thing that does not survive the mirror on its own: LivingEntity.hurt
        // pushes the target away from the attacker's REAL position, which is in the other copy and
        // dominated by however far the twin has drifted from its carriage — an essentially arbitrary
        // direction. Held here so the knockback handler can point it away from where the attacker
        // appears to be instead.
        inFlightTarget = target instanceof LivingEntity living ? living : null;
        inFlightAttackerMirrored = from;
        try {
            attacker.attack(target);
        } finally {
            inFlightTarget = null;
            inFlightAttackerMirrored = null;
        }

        LOGGER.info("[DungeonTrain] Portal puppet attack: {} hit {} across the midpoint",
            attacker.getName().getString(), target.getName().getString());
        return true;
    }

    /** The live pair with the attacker in one corridor and the target in the other, or {@code null}. */
    private static PortalPairIndex.Entry pairAcross(ServerPlayer attacker, Entity target) {
        for (PortalPairIndex.Entry entry : PortalPairIndex.all()) {
            PortalFrames frames = entry.frames();

            int attackerFrame = frames.frameAt(attacker.getX(), attacker.getY(), attacker.getZ());
            if (attackerFrame == PortalFrames.FRAME_NONE) continue;

            int targetFrame = frames.frameAt(target.getX(), target.getY(), target.getZ());
            if (targetFrame == PortalFrames.FRAME_NONE) continue;

            // Same corridor means they are standing together and no puppet is involved — an ordinary
            // swing already reaches, and letting this path serve it would sidestep the reach check
            // vanilla does on the interact packet.
            if (targetFrame == attackerFrame) continue;

            return entry;
        }
        return null;
    }

    /** The living target of the attack in progress, or {@code null} when none is. */
    public static LivingEntity inFlightTarget() {
        return inFlightTarget;
    }

    /** Where the attacker appears to stand in the target's copy, for knockback direction. */
    public static Vec3 inFlightAttackerMirrored() {
        return inFlightAttackerMirrored;
    }
}
