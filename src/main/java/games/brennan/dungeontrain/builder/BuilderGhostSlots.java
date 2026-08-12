package games.brennan.dungeontrain.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the Train Builder draws the rest of the train when it isn't parking all of it.
 *
 * <p>Opening a carriage room or a part parks one carriage ({@link BuilderWorldLayout#parkedCarriages})
 * because one template is one carriage. That is the right thing to <em>edit</em> and the wrong thing
 * to <em>look at</em>: a carriage on its own tells you nothing about how it reads in a run, which is
 * most of why anyone opens the outside view at all. The rest of the group — the other carriages and
 * the flatbed pads that cap it — is therefore drawn as translucent ghosts; see
 * {@code BuilderGhostTrainRenderer}.</p>
 *
 * <p><b>Drawn, never stamped.</b> Nothing here touches the world, so a ghost cannot be edited, cannot
 * be saved into a template, and cannot confuse the dirty check — which is exactly why this is a
 * render-time geometry table and not a second call to {@code stampTrain}.</p>
 *
 * <p>The group is laid out from the same arithmetic {@link BuilderWorldLayout#trainStartX} stamps
 * from — {@code [BACK pad | n × enclosed | FRONT pad]}, centred on the origin — so the ghosts land
 * on the slots a real group would have used, and the parked carriage falls into one of them rather
 * than beside them.</p>
 *
 * <p>Pure, so the arithmetic is testable without a client.</p>
 */
public final class BuilderGhostSlots {

    private static final Ghosts EMPTY = new Ghosts(List.of(), List.of(), 0);

    private BuilderGhostSlots() {}

    /**
     * The ghost geometry for one build, in world X.
     *
     * @param carriageMinX low-X block of each carriage slot that has no real carriage in it
     * @param padMinX      low-X block of each flatbed pad capping the group
     * @param padLength    how far a pad runs on X — {@code CarriagePlacer.halfPadLen}
     */
    public record Ghosts(List<Integer> carriageMinX, List<Integer> padMinX, int padLength) {

        public boolean isEmpty() {
            return carriageMinX.isEmpty() && padMinX.isEmpty();
        }
    }

    /**
     * Lay the full group out around the carriage that is actually parked.
     *
     * <p>Only the one-carriage case has a meaningful "rest of the train": with the full count parked
     * there is nothing missing, and there is no third arrangement to guess at.</p>
     *
     * <p>The slot the parked carriage occupies is dropped from the list rather than assumed to be the
     * middle one. It <em>is</em> the middle one for an odd group — both this and
     * {@link BuilderWorldLayout#trainStartX} centre on the origin — but matching on position means an
     * even group, or a layout change, degrades to one redundant ghost instead of a ghost drawn
     * straight through the blocks you are editing.</p>
     *
     * @param parkedMinX     low-X block of the carriage on the track
     * @param parked         carriages actually stamped
     * @param full           carriages this mode would park for a whole carriage
     * @param carriageLength one carriage's length on X
     * @param halfPadLen     {@code CarriagePlacer.halfPadLen} for these dims
     */
    public static Ghosts of(int parkedMinX, int parked, int full, int carriageLength, int halfPadLen) {
        if (parked != 1 || full <= 1 || carriageLength <= 0) {
            return EMPTY;
        }
        // Pads only wrap a run of more than one, exactly as usesPads says — so a group that would
        // not have had them does not get ghosts of them either.
        int pad = BuilderWorldLayout.usesPads(full) ? Math.max(0, halfPadLen) : 0;
        int total = full * carriageLength + 2 * pad;
        int startX = -total / 2;
        int enclosedX = startX + pad;

        List<Integer> carriages = new ArrayList<>();
        for (int i = 0; i < full; i++) {
            int x = enclosedX + i * carriageLength;
            if (x != parkedMinX) {
                carriages.add(x);
            }
        }
        List<Integer> pads = pad <= 0
                ? List.of()
                : List.of(startX, enclosedX + full * carriageLength);
        return new Ghosts(carriages, pads, pad);
    }
}
