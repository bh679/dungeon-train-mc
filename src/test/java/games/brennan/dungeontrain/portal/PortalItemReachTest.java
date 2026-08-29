package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reach rule behind {@link PortalItemReach#verdict}.
 *
 * <p>Pure geometry, so no Minecraft bootstrap: the boxes here stand in for a player's inflated
 * pickup box and an item's own, and the frames for the two copies of one corridor. What is being
 * checked is the decision, not the plumbing — {@link PortalEntityTransit} does the moving and needs
 * a live level to test.</p>
 *
 * <p>The case that matters most is the last one. An item between two players in two copies must
 * settle, because the alternative is not a wrong answer once but a wrong answer every tick, with
 * the item flickering between worlds while neither player can grab it.</p>
 */
final class PortalItemReachTest {

    private static final int CARRIAGE = PortalFrames.FRAME_CARRIAGE;
    private static final int TWIN = PortalFrames.FRAME_TWIN;

    /** An item-sized box at a point. */
    private static AABB item(double x, double y, double z) {
        return new AABB(x - 0.125, y, z - 0.125, x + 0.125, y + 0.25, z + 0.125);
    }

    /** A player-sized box at a point, inflated by the pickup reach, as a Reacher would be. */
    private static PortalItemReach.Reacher player(int frame, double x, double y, double z) {
        AABB body = new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        return new PortalItemReach.Reacher(frame, body.inflate(
            PortalItemReach.REACH_HORIZONTAL, PortalItemReach.REACH_VERTICAL,
            PortalItemReach.REACH_HORIZONTAL));
    }

    @Test
    @DisplayName("nobody reaching: the midpoint rule keeps its say")
    void nobodyReaching() {
        assertEquals(PortalItemReach.Verdict.NONE,
            PortalItemReach.verdict(TWIN, item(10, 0, 10), item(10, 100, 10),
                List.of(player(CARRIAGE, 40, 100, 40))));
    }

    @Test
    @DisplayName("reached from the other copy: pull it across")
    void reachedFromTheOtherCopy() {
        assertEquals(PortalItemReach.Verdict.PULL,
            PortalItemReach.verdict(TWIN, item(10, 0, 10), item(10, 100, 10),
                List.of(player(CARRIAGE, 10.5, 100, 10))));
    }

    @Test
    @DisplayName("a player in the item's own copy holds it — the midpoint rule must not take it")
    void reachedFromItsOwnCopy() {
        assertEquals(PortalItemReach.Verdict.HOLD,
            PortalItemReach.verdict(TWIN, item(10, 0, 10), item(10, 100, 10),
                List.of(player(TWIN, 10.5, 0, 10))));
    }

    @Test
    @DisplayName("in the other copy but out of arm's reach: no pull")
    void outOfReach() {
        assertEquals(PortalItemReach.Verdict.NONE,
            PortalItemReach.verdict(TWIN, item(10, 0, 10), item(10, 100, 10),
                List.of(player(CARRIAGE, 14, 100, 10))));
    }

    @Test
    @DisplayName("one player either side: it stays with the one standing over it, whatever the order")
    void nearBeatsFarEitherOrder() {
        PortalItemReach.Reacher near = player(TWIN, 10.5, 0, 10);
        PortalItemReach.Reacher far = player(CARRIAGE, 10.5, 100, 10);

        assertEquals(PortalItemReach.Verdict.HOLD,
            PortalItemReach.verdict(TWIN, item(10, 0, 10), item(10, 100, 10), List.of(near, far)));
        assertEquals(PortalItemReach.Verdict.HOLD,
            PortalItemReach.verdict(TWIN, item(10, 0, 10), item(10, 100, 10), List.of(far, near)));
    }

    @Test
    @DisplayName("an item in neither corridor has no counterpart, and so no verdict")
    void noMirror() {
        assertEquals(PortalItemReach.Verdict.NONE,
            PortalItemReach.verdict(PortalFrames.FRAME_NONE, item(10, 0, 10), null,
                List.of(player(CARRIAGE, 10.5, 100, 10))));
    }
}
