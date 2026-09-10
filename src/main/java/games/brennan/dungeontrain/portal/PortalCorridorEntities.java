package games.brennan.dungeontrain.portal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Everything physically standing in one pair's two corridors.
 *
 * <p>Shared by the two things that act on a corridor's occupants — {@link PortalEntityTransit},
 * which carries them across the midpoint, and {@link PortalPuppets}, which stands them up in the
 * opposite copy. One scan, one definition of "in the corridor", so the two cannot disagree about an
 * entity at the threshold: a mob that transits must be a mob that had a puppet, or it would appear
 * to teleport rather than to walk through.</p>
 */
public final class PortalCorridorEntities {

    private PortalCorridorEntities() {}

    /** The world-space box of one corridor, exactly the volume {@code insideCorridor} describes. */
    public static AABB worldBox(PortalFrames frames, int frame) {
        PortalCarriageLayout.Bounds b = frames.layout().localBounds();
        PortalFrames.Origin o = frames.originOf(frame);
        return new AABB(
            o.x() + b.minX(), o.y() + b.minY(), o.z() + b.minZ(),
            o.x() + b.maxX(), o.y() + b.maxY(), o.z() + b.maxZ());
    }

    /**
     * Every entity in either corridor of the pair, in the order the level reported them.
     *
     * <p>Deduplicated, though the two boxes cannot overlap in practice — a twin sits at the world
     * floor and its carriage rides the train — so that neither caller has to reason about whether
     * they can.</p>
     *
     * <p><b>Through a set, not a list scan.</b> This ran per pair per tick with a linear
     * {@code contains} over the accumulating list, which is fine for the handful of entities a
     * corridor was assumed to hold and quadratic for what an authored room actually holds: 64 mobs
     * by {@link PortalRoomMobs#MAX_LIVE_PER_STRUCTURE}, plus every item and orb a fight in it
     * drops. Identity semantics, matching the reference comparison the list scan did.</p>
     */
    public static List<Entity> inCorridors(ServerLevel level, PortalFrames frames) {
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Entity> out = new ArrayList<>();
        collect(level, frames, PortalFrames.FRAME_CARRIAGE, seen, out);
        collect(level, frames, PortalFrames.FRAME_TWIN, seen, out);
        return out;
    }

    private static void collect(ServerLevel level, PortalFrames frames, int frame,
                                Set<Entity> seen, List<Entity> out) {
        addNew(level.getEntities((Entity) null, worldBox(frames, frame), e -> true), seen, out);
    }

    /**
     * Append everything not seen before, in the order given.
     *
     * <p>Split out and generic purely so the de-duplication rule can be tested without a level to
     * put entities in — {@code seen} carries the identity semantics, so this stays a plain "add if
     * new" and has no opinion of its own about what equality means.</p>
     */
    static <T> void addNew(Iterable<T> from, Set<T> seen, List<T> out) {
        for (T item : from) {
            if (seen.add(item)) out.add(item);
        }
    }
}
