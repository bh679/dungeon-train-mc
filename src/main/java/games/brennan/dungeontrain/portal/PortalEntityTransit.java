package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;

/**
 * Carries <b>everything else</b> across the midpoint, on the same rule that carries players.
 *
 * <p>Without this a corridor is only half a portal. A villager that walks in keeps walking in the
 * carriage while the player who followed it is in the twin, so the two part company inside a room
 * that is supposed to be one place; a thrown ender pearl sails through the crossing and lands in the
 * copy the thrower is no longer in. The rule that makes the illusion work for a player —
 * {@link PortalFrames#requiredMove} — is about a position, not about being a player, so it applies
 * unchanged.</p>
 *
 * <p><b>No cooldown, unlike the player swap.</b> That cooldown exists for the network round trip: a
 * teleported player's client has not yet acknowledged the move, so for a tick or two the server is
 * judging a position the client has not agreed to. Nothing else has a client to disagree with — the
 * server is the only authority on where a villager is — so the hysteresis band alone settles it, and
 * the rule's idempotence does the rest.</p>
 *
 * <p><b>Momentum survives.</b> {@code teleportTo} moves an entity without touching its velocity,
 * which is what an ender pearl needs: it arrives in the twin still travelling, at the speed and
 * bearing it had, and carries on down the corridor as though nothing happened.</p>
 */
public final class PortalEntityTransit {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalEntityTransit() {}

    /** Move every entity in the pair's corridors that is on the wrong side of its midpoint. */
    public static void run(ServerLevel level, PortalFrames frames, List<Entity> entities,
                           int carriageIndex) {
        run(level, frames, entities, carriageIndex, null);
    }

    /**
     * As {@link #run(ServerLevel, PortalFrames, List, int)}, but landing anything walking <b>in</b>
     * at {@code twinOverride} instead of the frame's own twin.
     *
     * <p>What makes a led villager arrive where its player does. A player who came out through an
     * extra corridor eight rooms out walks back in to that corridor ({@link PortalExitBindings});
     * without the same override here the villager behind them goes to the original twin instead, and
     * the two part company inside a room that is supposed to be one place — which is the exact bug
     * this class exists to prevent, reappearing one level up.</p>
     *
     * <p>Null means the original twin, so the ordinary call above is this one with nothing to say.</p>
     */
    public static void run(ServerLevel level, PortalFrames frames, List<Entity> entities,
                           int carriageIndex, PortalFrames.Origin twinOverride) {
        for (Entity entity : entities) {
            if (!eligible(entity)) continue;

            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            PortalFrames.Move move = frames.redirectedTo(frames.requiredMove(x, y, z), twinOverride);
            if (move == null) continue;

            // Same one-way gate the player swap carries, and for the same reason: a severed corridor
            // takes nothing in, but everything already in the room can still come back out. Without
            // this a villager led in before the break would be walled off from the train while its
            // player walked back through.
            if (move.toFrame() == PortalFrames.FRAME_TWIN
                && PortalSever.isSevered(level, carriageIndex)) {
                continue;
            }

            // Grounded entities land on the destination floor's surface rather than the
            // carried-across local Y, for the reason the player swap does it: the two frames' block
            // grids differ by the ship's fractional pose, and a fraction inside the floor of a twin
            // that hangs in open air is resolved by dropping through it.
            double targetY = entity.onGround()
                ? frames.floorSurfaceY(move.toFrame(), twinOverride)
                : move.y();

            Vec3 velocity = entity.getDeltaMovement();
            entity.teleportTo(move.x(), targetY, move.z());
            entity.setDeltaMovement(velocity);

            // The path it was following leads back to a place it is no longer near — in the twin's
            // case, forty-odd blocks straight up. Dropping it makes the mob look around and decide
            // again from where it now is, rather than spend a moment walking into a wall.
            if (entity instanceof Mob mob) {
                mob.getNavigation().stop();
                if (move.toFrame() == PortalFrames.FRAME_TWIN) markPersistent(mob);
            }

            LOGGER.info("[DungeonTrain] Portal carriage transit: entity={} carriage={} → {} ({}, {}, {}) → ({}, {}, {})",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), carriageIndex,
                move.toFrame() == PortalFrames.FRAME_TWIN ? "TWIN" : "CARRIAGE",
                fmt(x), fmt(y), fmt(z), fmt(move.x()), fmt(targetY), fmt(move.z()));
        }
    }

    /**
     * Make a mob that has gone through into the portal world stay there.
     *
     * <p>The room is at the bottom of the world and the train leaves it behind, so to vanilla its
     * occupants are mobs nobody is near, and the distance rule reaps them.
     * {@code PortalDespawnEvents} covers the room while it is being used; this covers the rest —
     * walking a villager through a portal is a deliberate act, and it should still be there an hour
     * later rather than only while somebody is standing next to it.</p>
     *
     * <p>The same flag vanilla sets for a mob you have name-tagged or traded with, and one-way for
     * the same reason: there is no un-set in the API. So a mob that merely wandered through is
     * persistent from then on, which is why it is logged — a lasting change to a mob deserves a line
     * as much as a lasting change to the world does.</p>
     */
    private static void markPersistent(Mob mob) {
        if (mob.isPersistenceRequired()) return;

        mob.setPersistenceRequired();
        LOGGER.info("[DungeonTrain] Portal transit made {} persistent — it is in the portal world now",
            BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
    }

    /**
     * Whether an entity is the sort of thing that travels through a corridor.
     *
     * <p>Players are excluded because {@code PortalCarriageEvents} already moves them, with the
     * relative teleport and the acknowledgement cooldown a client needs. Passengers are excluded
     * because their position is their vehicle's to decide — move the vehicle and they come along.</p>
     *
     * <p>The interesting exclusion is <b>fixtures</b>: paintings, item frames and End Crystals are
     * decor, not travellers. They are also the one category the rule would quietly damage — a
     * painting hung in the far half of a corridor is permanently on the wrong side of the midpoint,
     * so it would migrate to the twin the moment anyone walked past and never come back. Blocks in a
     * corridor are mirrored rather than moved, and these behave like blocks. The same predicate
     * {@code TrainStaticContentsCarrier} uses for "does not move itself".</p>
     */
    private static boolean eligible(Entity entity) {
        if (entity instanceof ServerPlayer) return false;
        if (entity.isPassenger()) return false;
        if (!entity.isAlive()) return false;
        return !isFixture(entity);
    }

    /** Decor that stays where it was hung. Mirrors {@code TrainStaticContentsCarrier}'s set. */
    private static boolean isFixture(Entity entity) {
        return entity instanceof EndCrystal || entity instanceof HangingEntity;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
