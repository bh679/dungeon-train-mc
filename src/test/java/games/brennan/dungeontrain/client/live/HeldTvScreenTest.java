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
    @DisplayName("hand contexts spin the set so the model's 45°/225° yaw ends up facing the viewer; others keep NONE")
    void poses() {
        float modelRight = 45f, modelLeft = 225f;
        assertEquals(180f, modelRight + HeldTvScreen.poseFor(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND).yaw(), 1e-4);
        assertEquals(180f, modelLeft + HeldTvScreen.poseFor(ItemDisplayContext.FIRST_PERSON_LEFT_HAND).yaw(), 1e-4);
        assertNotEquals(HeldTvScreen.Pose.NONE, HeldTvScreen.poseFor(ItemDisplayContext.THIRD_PERSON_LEFT_HAND));
        assertEquals(HeldTvScreen.poseFor(ItemDisplayContext.THIRD_PERSON_LEFT_HAND),
            HeldTvScreen.poseFor(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND));
        for (ItemDisplayContext c : ItemDisplayContext.values()) {
            if (!HeldTvScreen.isHandContext(c)) assertEquals(HeldTvScreen.Pose.NONE, HeldTvScreen.poseFor(c), c.name());
        }
    }

    @Test
    @DisplayName("the two first-person poses mirror each other across the hand")
    void firstPersonMirrors() {
        HeldTvScreen.Pose r = HeldTvScreen.poseFor(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        HeldTvScreen.Pose l = HeldTvScreen.poseFor(ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
        assertEquals(r.pitch(), l.pitch());
        assertEquals(-r.dx(), l.dx(), 1e-6);
        assertEquals(r.dy(), l.dy());
        assertEquals(r.dz(), l.dz());
        assertTrue(HeldTvScreen.HALF > 0 && HeldTvScreen.HALF < 0.5f);
    }
}
