package games.brennan.dungeontrain.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the Train Builder draws the rest of the train when it isn't parking all of it.
 *
 * <p>Opening a carriage room or a part parks one carriage ({@link BuilderWorldLayout#parkedCarriages})
 * because one template is one carriage. That is the right thing to <em>edit</em> and the wrong thing
 * to <em>look at</em>: a carriage on its own tells you nothing about how it reads in a run, which is
 * most of why anyone opens the outside view at all. The missing slots are therefore drawn as
 * translucent ghosts — see {@code BuilderGhostTrainRenderer}.</p>
 *
 * <p><b>Drawn, never stamped.</b> Nothing here touches the world, so a ghost cannot be edited, cannot
 * be saved into a template, and cannot confuse the dirty check — which is exactly why this is a
 * render-time offset table and not a second call to {@code stampTrain}.</p>
 *
 * <p>Pure, so the arithmetic is testable without a client.</p>
 */
public final class BuilderGhostSlots {

    private BuilderGhostSlots() {}

    /**
     * X offsets, in blocks, from the parked carriage to each ghost slot.
     *
     * <p>The ghosts grow outwards alternately — one to the west, one to the east, and so on — so the
     * carriage you are editing stays in the middle of the run rather than at one end of it. That
     * matters for the view it exists to give: a build centred on the platform standoff should have
     * the train continuing on both sides of it, the way it would in a real train.</p>
     *
     * <p>Empty when nothing is missing, which is the common case: a whole carriage parks the mode's
     * full count, so it has no empty slots to fill.</p>
     *
     * @param parked         carriages actually stamped on the track
     * @param full           carriages this mode would park for a whole carriage
     * @param carriageLength one carriage's length on X, the pitch between slots
     */
    public static List<Integer> offsets(int parked, int full, int carriageLength) {
        List<Integer> offsets = new ArrayList<>();
        // Only the one-carriage case has a meaningful "rest of the train": with the full count
        // parked there is nothing missing, and there is no third arrangement to guess at.
        if (parked != 1 || full <= 1 || carriageLength <= 0) {
            return offsets;
        }
        for (int i = 1; i < full; i++) {
            int step = (i + 1) / 2;                 // 1, 1, 2, 2, 3, 3, ...
            int side = (i % 2 == 1) ? -1 : 1;       // west, east, west, east, ...
            offsets.add(side * step * carriageLength);
        }
        return offsets;
    }
}
