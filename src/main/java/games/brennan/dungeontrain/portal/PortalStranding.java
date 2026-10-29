package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Who a pair whose train has gone is actually stranding.
 *
 * <p>Standing inside a dead pair's structure box is not enough. Pairs are stacked on Y lanes, and the
 * same X can carry a dead pair's room one lane under a working one: a player walking the working room
 * is in a structure whose corridors lead somewhere, and carrying them back to the train because of a
 * neighbour's box is a teleport out of a portal that was doing its job (2026-10-21, AshSeven pulled
 * out of pair -138's room three times by pair -105's rescue). So a player inside any <b>live</b> pair's
 * structure belongs to that pair, whatever else they are also inside.</p>
 *
 * <p>No Minecraft bootstrap needed — {@link AABB} and {@link Vec3} are plain value types — so it
 * unit-tests directly.</p>
 */
public final class PortalStranding {

    private PortalStranding() {}

    /**
     * The players inside {@code deadBox} and outside every one of {@code liveBoxes}, in list order.
     *
     * @param position where a player stands — feet, the point every other structure test uses
     */
    public static <T> List<T> strandedIn(List<T> players, Function<T, Vec3> position, AABB deadBox,
                                         Collection<AABB> liveBoxes) {
        List<T> out = new ArrayList<>();
        for (T player : players) {
            Vec3 at = position.apply(player);
            if (!deadBox.contains(at) || insideAny(liveBoxes, at)) continue;
            out.add(player);
        }
        return out;
    }

    private static boolean insideAny(Collection<AABB> boxes, Vec3 at) {
        for (AABB box : boxes) {
            if (box.contains(at)) return true;
        }
        return false;
    }
}
