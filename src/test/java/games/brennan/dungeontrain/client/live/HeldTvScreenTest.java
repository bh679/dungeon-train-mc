package games.brennan.dungeontrain.client.live;

import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The held screen shows in the hands only, and every hand context gets a pose that turns it round. */
final class HeldTvScreenTest {

    @Test
    @DisplayName("only the four hand contexts show the screen")
    void handContexts() {
        for (ItemDisplayContext c : ItemDisplayContext.values()) {
            boolean hand = c == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || c == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || c == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || c == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            assertEquals(hand, HeldTvScreen.isHandContext(c), c.name());
        }
        assertFalse(HeldTvScreen.isHandContext(ItemDisplayContext.HEAD));
    }

    @Test
    @DisplayName("hand contexts turn and lift the set; other contexts keep NONE")
    void poses() {
        for (ItemDisplayContext c : ItemDisplayContext.values()) {
            HeldTvScreen.Pose p = HeldTvScreen.poseFor(c);
            if (HeldTvScreen.isHandContext(c)) {
                assertNotEquals(HeldTvScreen.Pose.NONE, p, c.name());
                assertTrue(p.yaw() > 0, c.name());
            } else {
                assertEquals(HeldTvScreen.Pose.NONE, p, c.name());
            }
        }
        assertTrue(HeldTvScreen.poseFor(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND).dy() > 0);
        assertTrue(HeldTvScreen.poseFor(ItemDisplayContext.FIRST_PERSON_LEFT_HAND).dy() > 0);
        assertEquals(HeldTvScreen.poseFor(ItemDisplayContext.THIRD_PERSON_LEFT_HAND),
            HeldTvScreen.poseFor(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND));
    }

    @Test
    @DisplayName("mirroring keeps yaw, pitch, lift and distance and flips only the sideways nudge")
    void mirrorRule() {
        HeldTvScreen.Pose r = new HeldTvScreen.Pose(120f, -10f, 0.3f, 0.7f, -0.4f);
        HeldTvScreen.Pose l = HeldTvScreen.mirrored(r);
        assertEquals(r.yaw(), l.yaw());
        assertEquals(r.pitch(), l.pitch());
        assertEquals(-r.dx(), l.dx(), 1e-6);
        assertEquals(r.dy(), l.dy());
        assertEquals(r.dz(), l.dz());
        assertTrue(HeldTvScreen.HALF > 0 && HeldTvScreen.HALF < 0.5f);
    }
}
