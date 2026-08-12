package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalExitSites.Site;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * What makes an endless room's extra corridors real ways back to the train rather than scenery.
 *
 * <h2>A copy is another {@link PortalFrames}, and nothing else</h2>
 * <p>The whole of the crossing rule is a mapping between two origins: the carriage's, read live from
 * the ship, and the twin's. A copy is stamped from the same layout as the original
 * ({@code PortalCarriageBuilder.stampExitCopy}) and differs from it only in where it stands — so a
 * frame pairing the <b>same carriage</b> with the copy's origin is a complete, correct description
 * of walking through it. {@link PortalFrames#requiredMove} then fires the swap, the side rule still
 * mirrors on {@link PortalCarriageRole}, the hysteresis band still absorbs the ship's jitter, and
 * {@code PortalEntityTransit} still carries a villager across. None of them had to learn anything.</p>
 *
 * <p>That is why this class is a list of frames rather than a second implementation of the rule. A
 * copy that took a player back through logic of its own would be a second place for the swap to be
 * subtly wrong, in a corridor a player cannot tell from the original.</p>
 *
 * <h2>The twin half only</h2>
 * <p>Each of these frames covers the carriage as well as its copy, and the carriage is already
 * covered by the pair's own frames. A player standing on the train therefore sits inside every one of
 * them at once, and left alone they would each try to send them somewhere — to a different copy each
 * tick. The caller must act only on positions {@link PortalFrames#frameAt} puts in
 * {@link PortalFrames#FRAME_TWIN}. Walking <i>in</i> from the train always leads to the original
 * twin, which is the pair's own business; a copy is only ever a way out.</p>
 *
 * <h2>Only copies somebody is near</h2>
 * <p>A structure can have a dozen copies standing while a player is beside one of them. Scanning the
 * rest for entities every tick would be work with nothing to find, so a copy is considered only when
 * a player is within {@link #NEAR_RANGE} of its box — which is a long way further than a corridor is
 * deep, so nothing that could reach the midpoint this tick is missed.</p>
 */
public final class PortalExitTransit {

    /**
     * How far outside a copy's corridor a player has to be before it is ignored, in blocks.
     *
     * <p>Comfortably more than a corridor is long ({@link PortalCorridorSize}: 13 at the default
     * dims), so a player anywhere near enough to walk through one this tick keeps it live — and a mob
     * inside one is still carried while its player waits outside the door.</p>
     */
    private static final double NEAR_RANGE = 24.0;

    private PortalExitTransit() {}

    /**
     * One live copy: which site it is, and the pairing that carries somebody out of it.
     *
     * <p>The site travels with the frames because leaving through a copy is what binds a player to
     * its tile ({@link PortalExitBindings}) — the caller has to know <i>which</i> copy fired, not
     * merely that one did.</p>
     */
    public record Copy(Site site, PortalFrames frames) {}

    /**
     * A frame for every standing copy of {@code role} that somebody is near, pairing it with the
     * carriage this call is for.
     *
     * <p>Role-matched on purpose: an {@link PortalCarriageRole#ENTRY} copy hands a player back to the
     * entry carriage and an {@link PortalCarriageRole#EXIT} copy to the exit carriage, exactly as the
     * originals do. {@code handlePortalCarriage} runs once per carriage, so each call picks up its
     * own half and the two never contend for the same copy.</p>
     */
    public static List<Copy> framesFor(PortalStructure structure, CarriageDims dims,
                                       PortalCarriageLayout layout, PortalCarriageRole role,
                                       PortalFrames.Origin carriage,
                                       List<ServerPlayer> players) {
        PortalExitCopies copies = structure.exitCopies();
        if (copies.isEmpty() || players.isEmpty()) return List.of();

        List<Copy> out = new ArrayList<>();
        for (Site site : copies.sites()) {
            if (site.role() != role) continue;
            BlockPos origin = structure.exitCopyOrigin(dims, site);
            PortalFrames frames = new PortalFrames(layout, carriage,
                new PortalFrames.Origin(origin.getX(), origin.getY(), origin.getZ()), role);
            if (!anyPlayerNear(frames, players)) continue;
            out.add(new Copy(site, frames));
        }
        return out;
    }

    /** True when this copy is worth looking at: somebody is inside it or close enough to walk in. */
    private static boolean anyPlayerNear(PortalFrames frames, List<ServerPlayer> players) {
        AABB near = PortalCorridorEntities.worldBox(frames, PortalFrames.FRAME_TWIN)
            .inflate(NEAR_RANGE);
        for (ServerPlayer player : players) {
            if (near.contains(player.getX(), player.getY(), player.getZ())) return true;
        }
        return false;
    }

    /**
     * True when {@code (x, y, z)} is inside this frame's copy rather than the carriage it shares with
     * the pair's own frames.
     *
     * <p>The guard the class javadoc describes, in one place so no caller has to remember it.</p>
     */
    public static boolean inCopy(PortalFrames frames, double x, double y, double z) {
        return frames.frameAt(x, y, z) == PortalFrames.FRAME_TWIN;
    }
}
