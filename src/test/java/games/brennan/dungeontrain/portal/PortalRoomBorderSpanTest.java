package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The World Border's height: out to the seal on each side, never into the world. */
final class PortalRoomBorderSpanTest {

    private static final int WORLD_MIN = -2048;
    private static final int WORLD_MAX = 320;
    private static final int BEDROCK = -64;
    private static final int ROOM_MIN = -1990;
    private static final int ROOM_MAX = -1976;

    @Test
    @DisplayName("In the basement: from the world's floor up to the bedrock seal, not past it")
    void basementRunsToTheBedrockSeal() {
        PortalSealPlane.Cut cut = PortalSealPlane.Cut.trimmed(Integer.MIN_VALUE, BEDROCK);
        assertArrayEquals(new int[] {WORLD_MIN, BEDROCK},
            PortalRoomBorderSpan.of(cut, null, WORLD_MIN, WORLD_MAX, ROOM_MIN, ROOM_MAX));
    }

    @Test
    @DisplayName("In an attic: from the lid's seal up to the top of the world")
    void atticRunsUpFromTheLid() {
        PortalSealPlane.Cut cut = PortalSealPlane.Cut.trimmed(200, Integer.MAX_VALUE);
        assertArrayEquals(new int[] {200, WORLD_MAX},
            PortalRoomBorderSpan.of(cut, null, WORLD_MIN, WORLD_MAX, 210, 224));
    }

    @Test
    @DisplayName("Seal culling off: the twin's own band stands in, so the border still stops at the seal")
    void noLiveCutFallsBackToTheTwinBand() {
        PortalSealPlane.Cut twin = PortalSealPlane.cutForTwin(ROOM_MIN, ROOM_MAX - 1, BEDROCK);
        assertArrayEquals(new int[] {WORLD_MIN, BEDROCK},
            PortalRoomBorderSpan.of(PortalSealPlane.Cut.NONE, twin, WORLD_MIN, WORLD_MAX,
                ROOM_MIN, ROOM_MAX));
    }

    @Test
    @DisplayName("No seal known at all: the room's own height, never the whole world")
    void noSealAtAllKeepsToTheRoom() {
        assertArrayEquals(new int[] {ROOM_MIN, ROOM_MAX},
            PortalRoomBorderSpan.of(PortalSealPlane.Cut.NONE, null, WORLD_MIN, WORLD_MAX,
                ROOM_MIN, ROOM_MAX));
    }
}
