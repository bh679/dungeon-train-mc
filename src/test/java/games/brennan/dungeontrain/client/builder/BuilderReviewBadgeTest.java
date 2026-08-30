package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The badge is the only mark a colour-blind player can read, so each state must actually get one. */
final class BuilderReviewBadgeTest {

    @Test
    @DisplayName("each decided state has an icon and its border colour; never-submitted has neither")
    void badges() {
        assertEquals(BuilderReviewState.BORDER_SUBMITTED,
                BuilderReviewBadge.of(BuilderReviewState.SUBMITTED).borderColour());
        assertEquals(BuilderReviewState.BORDER_ACCEPTED,
                BuilderReviewBadge.of(BuilderReviewState.ACCEPTED).borderColour());
        assertEquals(BuilderReviewState.BORDER_DECLINED,
                BuilderReviewBadge.of(BuilderReviewState.DECLINED).borderColour());
        assertNull(BuilderReviewBadge.of(BuilderReviewState.NONE));
        assertNull(BuilderReviewBadge.of("escalated"), "an unknown state is not marked, never mis-marked");
    }

    @Test
    @DisplayName("every icon a badge names is actually shipped")
    void spritesExist() {
        // A missing sprite is not a crash — it draws the magenta-and-black placeholder, in the corner
        // of a tile, at eight pixels. Which is to say it would ship.
        for (String review : new String[]{BuilderReviewState.SUBMITTED, BuilderReviewState.ACCEPTED,
                BuilderReviewState.DECLINED}) {
            BuilderReviewBadge badge = BuilderReviewBadge.of(review);
            assertNotNull(badge);
            // Off the classpath, not the source tree: that is what actually ends up in the jar, and
            // a test resolving a relative source path answers to whatever directory it was run from.
            String path = "/assets/" + badge.icon().getNamespace() + "/textures/gui/sprites/"
                    + badge.icon().getPath() + ".png";
            assertTrue(getClass().getResource(path) != null, "missing sprite: " + path);
        }
    }
}
