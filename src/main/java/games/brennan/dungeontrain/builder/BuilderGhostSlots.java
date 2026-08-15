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
        int pad = padLengthFor(full, halfPadLen);
        int total = full * carriageLength + 2 * pad;
        int enclosedX = -total / 2 + pad;

        List<Integer> carriages = new ArrayList<>();
        for (int i = 0; i < full; i++) {
            int x = enclosedX + i * carriageLength;
            if (x != parkedMinX) {
                carriages.add(x);
            }
        }
        return new Ghosts(carriages, padMinXFor(full, carriageLength, halfPadLen), pad);
    }

    /**
     * Where the flatbed pads of a {@code full}-carriage group sit, whatever is parked inside it.
     *
     * <p>Split out of {@link #of} because the pads and the empty carriage slots stopped being the
     * same question. A slot is only worth ghosting when it is <em>empty</em>, so {@code of} answers
     * nothing for a fully parked group — but the pads are ghosted either way. They are the frame
     * around the build rather than part of it ({@code BuilderBounds} deliberately leaves them out of
     * bounds), which is why they were being drawn solid and washed red: real blocks nobody may
     * touch, in a world where the red wash means "this will not be saved".</p>
     *
     * <p>Same arithmetic {@link BuilderWorldLayout#trainStartX} stamps from — {@code [BACK pad |
     * n × enclosed | FRONT pad]}, centred on the origin — and shared with {@code of} rather than
     * written twice, so a ghost pad cannot drift off a real one.</p>
     *
     * @return the low-X block of each pad, BACK first, or empty for a group that has none
     */
    public static List<Integer> padMinXFor(int full, int carriageLength, int halfPadLen) {
        int pad = padLengthFor(full, halfPadLen);
        if (pad <= 0 || carriageLength <= 0) {
            return List.of();
        }
        int startX = -(full * carriageLength + 2 * pad) / 2;
        return List.of(startX, startX + pad + full * carriageLength);
    }

    /**
     * How far a pad runs on X for this group, or 0 for a group that has none.
     *
     * <p>Pads only wrap a run of more than one, exactly as {@link BuilderWorldLayout#usesPads} says —
     * so a group that would not have had them does not get ghosts of them either.</p>
     */
    private static int padLengthFor(int full, int halfPadLen) {
        return BuilderWorldLayout.usesPads(full) ? Math.max(0, halfPadLen) : 0;
    }
}
