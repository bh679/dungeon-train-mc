package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.StreamPreviewRegions.Part;
import games.brennan.dungeontrain.client.live.StreamPreviewRegions.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamPreviewRegionsTest {

    private static final int W = 480;
    private static final int H = 270;

    private static StreamPreviewRegions regions() {
        // Pinned feed top-right, a 100 px name tag, chat 320 wide and 90 tall.
        return StreamPreviewRegions.of(W, H, new Rect(370, 4, 106, 60), 100, 13, 320, 90);
    }

    @Test
    void eachPartIsWhereItsHudElementIs() {
        StreamPreviewRegions r = regions();
        assertEquals(Part.HEAD_VIEWER, r.pick(400, 30));
        assertEquals(Part.NAME_TAG, r.pick(W / 2f, 8));
        assertEquals(Part.CHAT, r.pick(20, H - 60));
        assertEquals(Part.GUI, r.pick(W / 2f, H - 5));
        assertNull(r.pick(W / 2f, H / 2f), "the open world is no part");
    }

    @Test
    void overlapsGoToTheSmallerTopmostPart() {
        // Chat (0..328 wide) runs under the hotbar's left half (149..331); the hotbar row wins only
        // below chat's bottom edge, which sits 40 px above the screen's.
        StreamPreviewRegions r = regions();
        assertEquals(Part.CHAT, r.pick(200, H - 45));
        assertEquals(Part.GUI, r.pick(200, H - 20));
        // A name tag wide enough to run under the pinned feed: the feed wins.
        StreamPreviewRegions wide = StreamPreviewRegions.of(W, H, new Rect(370, 4, 106, 60), 300, 13, 320, 90);
        assertEquals(Part.HEAD_VIEWER, wide.pick(380, 8));
        assertEquals(Part.NAME_TAG, wide.pick(W / 2f, 8));
    }

    @Test
    void aNameTagSetToNoneStaysClickable() {
        StreamPreviewRegions r = StreamPreviewRegions.of(W, H, new Rect(370, 4, 106, 60), 0, 13, 320, 90);
        assertEquals(StreamPreviewRegions.TAG_PLACEHOLDER_W, r.rect(Part.NAME_TAG).w());
        assertEquals(Part.NAME_TAG, r.pick(W / 2f, 8));
    }
}
