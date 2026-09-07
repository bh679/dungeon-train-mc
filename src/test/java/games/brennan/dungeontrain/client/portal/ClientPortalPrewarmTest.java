package games.brennan.dungeontrain.client.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prewarm's decisions, which are the whole of what it can get wrong on its own: which sections
 * are worth building, in what order, and how long a destination is worth believing.
 *
 * <p>What it does with them — handing a section to the chunk builder — is vanilla's own call made on
 * vanilla's own terms, and is left to the in-game test.</p>
 */
class ClientPortalPrewarmTest {

    @AfterEach
    void clear() {
        ClientPortalPrewarm.reset();
    }

    @Test
    @DisplayName("nothing is claimed until a destination is armed")
    void quietUntilArmed() {
        assertFalse(ClientPortalPrewarm.live());
        assertEquals(0, ClientPortalPrewarm.claim().length);
    }

    @Test
    @DisplayName("a destination names the sections around it, nearest first")
    void spanIsNearestFirst() {
        long centre = ClientPortalPrewarm.packSection(4, -3, 17);
        long[] span = ClientPortalPrewarm.spanAround(centre);

        int width = ClientPortalPrewarm.RADIUS_SECTIONS_XZ * 2 + 1;
        int height = ClientPortalPrewarm.RADIUS_SECTIONS_Y * 2 + 1;
        assertEquals(width * height * width, span.length, "every section in the span appears");
        assertEquals(span.length, new HashSet<>(Arrays.stream(span).boxed().toList()).size(),
            "and appears once");

        // The destination's own section is what the player will be standing in, so it is built first
        // whatever else is queued behind it.
        assertEquals(centre, span[0]);

        int previous = -1;
        for (long section : span) {
            int dx = ClientPortalPrewarm.sectionX(section) - 4;
            int dy = ClientPortalPrewarm.sectionY(section) + 3;
            int dz = ClientPortalPrewarm.sectionZ(section) - 17;
            int distance = dx * dx + dy * dy + dz * dz;
            assertTrue(distance >= previous, "the span never walks back towards the destination");
            previous = distance;
        }
    }

    @Test
    @DisplayName("section coordinates survive the round trip, negatives included")
    void packingRoundTrips() {
        int[][] cases = {{0, 0, 0}, {4, -3, 17}, {-1, -1, -1}, {1873, 19, -2044}, {-2044, -20, 1873}};
        for (int[] c : cases) {
            long packed = ClientPortalPrewarm.packSection(c[0], c[1], c[2]);
            assertArrayEquals(c, new int[]{
                ClientPortalPrewarm.sectionX(packed),
                ClientPortalPrewarm.sectionY(packed),
                ClientPortalPrewarm.sectionZ(packed)},
                Arrays.toString(c));
        }
    }

    @Test
    @DisplayName("a claim takes the next few and comes back round for the rest")
    void claimsCycleThroughTheSpan() {
        ClientPortalPrewarm.arm(64, -40, 272);
        long[] span = ClientPortalPrewarm.spanAround(ClientPortalPrewarm.packSection(4, -3, 17));

        long[] first = ClientPortalPrewarm.claim();
        assertEquals(ClientPortalPrewarm.SECTIONS_PER_TICK, first.length);
        assertArrayEquals(Arrays.copyOf(span, first.length), first, "nearest first, in order");

        // Round the whole span and back to the start: a section that could not be built because its
        // chunk had not arrived gets another go, which is why no "already done" set is kept.
        Set<Long> seen = new HashSet<>();
        for (long section : first) seen.add(section);
        int claims = 1;
        while (seen.size() < span.length && claims < 100) {
            for (long section : ClientPortalPrewarm.claim()) seen.add(section);
            claims++;
        }
        assertEquals(span.length, seen.size(), "the whole span is covered");
        assertTrue(claims <= span.length, "and covered in a walk, not by chance");
    }

    @Test
    @DisplayName("re-arming the same section keeps the pass where it is; a new one starts again")
    void rearmingIsCheapUntilTheDestinationMoves() {
        ClientPortalPrewarm.arm(64, -40, 272);
        long[] first = ClientPortalPrewarm.claim();

        // A step down the corridor that lands in the same section: the walk carries on where it was.
        ClientPortalPrewarm.arm(70, -38, 275);
        long[] second = ClientPortalPrewarm.claim();
        assertFalse(Arrays.equals(first, second), "the pass continued rather than restarting");

        // A different section — a player bound to another copy — starts the span again.
        ClientPortalPrewarm.arm(64 + 64, -40, 272);
        long[] third = ClientPortalPrewarm.claim();
        assertEquals(ClientPortalPrewarm.packSection((64 + 64) >> 4, -40 >> 4, 272 >> 4), third[0]);
    }

    @Test
    @DisplayName("resetting forgets the destination outright")
    void resetForgets() {
        ClientPortalPrewarm.arm(64, -40, 272);
        assertTrue(ClientPortalPrewarm.live());

        ClientPortalPrewarm.reset();
        assertFalse(ClientPortalPrewarm.live());
        assertEquals(0, ClientPortalPrewarm.claim().length);
    }
}
