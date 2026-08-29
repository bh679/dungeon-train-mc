package games.brennan.dungeontrain.portal;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Which copy a dropped item belongs in when somebody is reaching for it.
 *
 * <p>A pair moves players and everything else by two <b>different</b> rules, and until there was a
 * reason to drop something in a corridor that never showed. A player's copy is decided by which way
 * they are looking ({@link PortalFrames#requiredMoveFacing}, via {@code PortalCarriageEvents}); an
 * item's is decided by which side of the midpoint it lies on
 * ({@link PortalFrames#requiredMove}, via {@link PortalEntityTransit}). The two are free to
 * disagree about the same cell, and when they do the item is in the other world: you see its
 * {@link PortalPuppets puppet}, which has no entity behind it, and vanilla's pickup never fires. You
 * can be standing on your own diamonds and unable to touch them.</p>
 *
 * <p>Blocks never had this problem because a corridor's blocks are <i>mirrored</i> into both copies.
 * An item cannot be — there is only one of it, which is the whole point — so instead it goes to
 * whoever reaches for it.</p>
 *
 * <p><b>A verdict, not a mover.</b> This answers the question inside {@link PortalEntityTransit}'s
 * existing rule rather than shoving items around after it. A separate pass would fight it: transit
 * would put the item back on the wrong side of the midpoint on the very next tick and the item
 * would ping-pong instead of being collected. {@link Verdict#HOLD} is the other half of that — while
 * somebody is reaching for an item, the midpoint rule must not teleport it out of their hands.</p>
 *
 * <p><b>Nothing here picks anything up.</b> Once the item is in the right copy, vanilla's own
 * {@code Player.aiStep} collects it, with its pickup delay, its ownership rules, its stack merging
 * and its mending. Reproducing any of that would be a second pickup path to get wrong.</p>
 */
public final class PortalItemReach {

    /**
     * Vanilla's own pickup reach, as {@code Player.aiStep} inflates its box before offering entities
     * to {@code playerTouch}. Taken from there rather than picked, so "close enough to grab" means
     * the same thing in a corridor as it does anywhere else in the game.
     */
    public static final double REACH_HORIZONTAL = 1.0;
    public static final double REACH_VERTICAL = 0.5;

    private PortalItemReach() {}

    /** What the reach rule has to say about one item. */
    public enum Verdict {
        /** Somebody in the other copy is reaching for it and nobody here is: bring it to them. */
        PULL,
        /** Somebody here is reaching for it: leave it alone, midpoint rule included. */
        HOLD,
        /** Nobody is reaching for it. The midpoint rule decides, as it always did. */
        NONE
    }

    /**
     * One player who could pick something up, and the copy they are standing in.
     *
     * @param frame     the corridor they are physically in — {@link PortalFrames#FRAME_CARRIAGE} or
     *                  {@link PortalFrames#FRAME_TWIN}
     * @param pickupBox their bounding box, inflated by vanilla's pickup reach
     */
    public record Reacher(int frame, AABB pickupBox) {

        public static Reacher of(Player player, int frame) {
            return new Reacher(frame,
                player.getBoundingBox().inflate(REACH_HORIZONTAL, REACH_VERTICAL, REACH_HORIZONTAL));
        }
    }

    /**
     * The verdict for an item at {@code here} in {@code itemFrame}, whose counterpart position in the
     * other copy is {@code mirrored}.
     *
     * <p>Boxes rather than points, and intersection rather than containment, because that is how
     * vanilla decides: an item is collected when its box meets the player's inflated one, not when
     * its centre is inside it.</p>
     *
     * <p><b>Near beats far, whatever the order.</b> A reacher in the item's own copy returns
     * immediately, so an item lying between two players in two copies stays where it is rather than
     * flicking between them each tick — and the player it is actually next to gets it. That
     * asymmetry is the whole tie-break; with only two copies there is never a choice of
     * <i>destination</i> to make, only whether to go at all.</p>
     *
     * @param mirrored may be {@code null} when the item is in neither corridor, which decides nothing
     */
    public static Verdict verdict(int itemFrame, AABB here, @Nullable AABB mirrored,
                                  List<Reacher> reachers) {
        boolean reachedFromOther = false;

        for (Reacher reacher : reachers) {
            if (reacher.frame() == PortalFrames.FRAME_NONE) continue;

            if (reacher.frame() == itemFrame) {
                if (reacher.pickupBox().intersects(here)) return Verdict.HOLD;
            } else if (mirrored != null && reacher.pickupBox().intersects(mirrored)) {
                reachedFromOther = true;
            }
        }

        return reachedFromOther ? Verdict.PULL : Verdict.NONE;
    }
}
