package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
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
 * <p><b>Items are asked one further question first.</b> An item is the one traveller with no rule
 * of its own to follow and somebody else's hands to end up in, so {@link PortalItemReach} decides
 * for it whenever a player is reaching — see there for why the midpoint rule alone leaves you
 * standing on diamonds you cannot touch.</p>
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
        // Built once, and only if this corridor actually holds a loose item — which is nearly never.
        List<PortalItemReach.Reacher> reachers = null;

        for (Entity entity : entities) {
            if (!eligible(entity)) continue;

            double x = entity.getX(), y = entity.getY(), z = entity.getZ();
            boolean pulled = false;
            PortalFrames.Move move;

            if (entity instanceof ItemEntity) {
                if (reachers == null) reachers = reachersIn(frames, entities);
                PortalFrames.Move mirrored = frames.mirror(x, y, z);
                PortalItemReach.Verdict verdict = PortalItemReach.verdict(
                    frames.frameAt(x, y, z), entity.getBoundingBox(),
                    mirrored == null ? null : boxAt(entity, mirrored), reachers);

                // Somebody here has their hands on it. The midpoint rule does not get to take it
                // off them — that is the failure this whole verdict exists to prevent, and it is
                // just as bad when the rule pushes as when it fails to.
                if (verdict == PortalItemReach.Verdict.HOLD) continue;

                pulled = verdict == PortalItemReach.Verdict.PULL;
                // A pull is never redirected: the player reaching for it is standing in THIS
                // pairing's corridor — that is how they were found — so the item goes to their copy
                // and not to whichever twin somebody's exit binding happens to name.
                move = pulled ? mirrored
                    : frames.redirectedTo(frames.requiredMove(x, y, z), twinOverride);
            } else {
                move = frames.redirectedTo(frames.requiredMove(x, y, z), twinOverride);
            }

            if (move == null) continue;

            // Same one-way gate the player swap carries, and for the same reason: a severed corridor
            // takes nothing in, but everything already in the room can still come back out. Without
            // this a villager led in before the break would be walled off from the train while its
            // player walked back through.
            if (PortalSever.blocksMove(move.toFrame(),
                PortalSever.isSevered(level, carriageIndex))) {
                continue;
            }

            // Grounded entities land on the destination floor's surface rather than the
            // carried-across local Y, for the reason the player swap does it: the two frames' block
            // grids differ by the ship's fractional pose, and a fraction inside the floor of a twin
            // that hangs in open air is resolved by dropping through it.
            //
            // Never an item, though. An item bobs, so its onGround flickers, and the two answers
            // differ by the pose's fraction — it would arrive at one of two heights depending on
            // which tick it happened to cross. The carried-across Y already includes the two
            // origins' difference, which is the whole of what the clamp is correcting for, and an
            // item that ends up a fraction low is pushed out rather than dropped through.
            double targetY = entity.onGround() && !(entity instanceof ItemEntity)
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

            LOGGER.info("[DungeonTrain] Portal carriage transit: entity={} carriage={} → {} ({}, {}, {}) → ({}, {}, {}){}",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), carriageIndex,
                move.toFrame() == PortalFrames.FRAME_TWIN ? "TWIN" : "CARRIAGE",
                fmt(x), fmt(y), fmt(z), fmt(move.x()), fmt(targetY), fmt(move.z()),
                pulled ? " (pulled — a player in that copy is reaching for it)" : "");
        }
    }

    /**
     * The players standing in either of this pair's corridors, as things that can pick something up.
     *
     * <p>Read out of the occupant list the caller already scanned rather than asked of the level
     * again — the same one scan {@code PortalCorridorEntities} exists to share, so a player the
     * puppets can see is exactly a player who can reach.</p>
     */
    private static List<PortalItemReach.Reacher> reachersIn(PortalFrames frames,
                                                            List<Entity> entities) {
        List<PortalItemReach.Reacher> out = new ArrayList<>();
        for (Entity entity : entities) {
            if (!(entity instanceof Player player)) continue;
            // A spectator has no hands. Vanilla will not give them the item either, so pulling one
            // across for them would strand it in a copy nobody solid is standing in.
            if (player.isSpectator()) continue;

            int frame = frames.frameAt(player.getX(), player.getY(), player.getZ());
            if (frame == PortalFrames.FRAME_NONE) continue;
            out.add(PortalItemReach.Reacher.of(player, frame));
        }
        return out;
    }

    /** The entity's own box, moved to where its counterpart stands in the other copy. */
    private static AABB boxAt(Entity entity, PortalFrames.Move at) {
        return entity.getBoundingBox().move(
            at.x() - entity.getX(), at.y() - entity.getY(), at.z() - entity.getZ());
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
