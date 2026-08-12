package games.brennan.dungeontrain.portal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

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
     * Every entity in either corridor of the pair.
     *
     * <p>Deduplicated, though the two boxes cannot overlap in practice — a twin sits at the world
     * floor and its carriage rides the train. It costs one list scan over a handful of entities and
     * means neither caller has to reason about whether they can.</p>
     */
    public static List<Entity> inCorridors(ServerLevel level, PortalFrames frames) {
        List<Entity> out = new ArrayList<>();
        collect(level, frames, PortalFrames.FRAME_CARRIAGE, out);
        collect(level, frames, PortalFrames.FRAME_TWIN, out);
        return out;
    }

    private static void collect(ServerLevel level, PortalFrames frames, int frame, List<Entity> out) {
        for (Entity entity : level.getEntities((Entity) null, worldBox(frames, frame), e -> true)) {
            if (!out.contains(entity)) out.add(entity);
        }
    }
}
