package games.brennan.dungeontrain.client.live;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The parts of the Options Stream preview a click toggles, as rectangles in GUI pixels of your own
 * screen (the preview maps them through the broadcast's crop). Pure geometry, so it is unit-tested.
 *
 * <p>Parts can overlap (a long chat line under the hotbar, a wide name tag under the pinned feed), so
 * {@link #pick} tries them in {@link Part} order: smallest and topmost first.</p>
 */
public final class StreamPreviewRegions {

    /** What a click on the preview toggles; declared in hit-test priority. */
    public enum Part { HEAD_VIEWER, NAME_TAG, CHAT, GUI }

    /** A rectangle in GUI pixels. */
    public record Rect(float x, float y, float w, float h) {
        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** Vanilla's hotbar width, and the hearts, food and XP bar above it. */
    static final int HUD_W = 182;
    static final int HUD_H = 49;
    /** Vanilla draws chat's bottom line this far above the screen's bottom edge. */
    static final int CHAT_BOTTOM = 40;
    /** Chat lines carry this much background either side of the text. */
    static final int CHAT_PAD = 4;
    /** A name tag set to None keeps a box this wide, so it can be clicked back on. */
    static final int TAG_PLACEHOLDER_W = 80;

    private final Map<Part, Rect> rects;

    private StreamPreviewRegions(Map<Part, Rect> rects) {
        this.rects = rects;
    }

    /**
     * @param headViewer the pinned feed's box, in GUI pixels
     * @param tagW       the name tag pill's width (0 when there is none), {@code tagH} its height
     * @param chatW      chat's scaled width, {@code chatH} its scaled height
     */
    public static StreamPreviewRegions of(int guiW, int guiH, Rect headViewer, float tagW, float tagH, float chatW, float chatH) {
        Map<Part, Rect> r = new EnumMap<>(Part.class);
        r.put(Part.HEAD_VIEWER, headViewer);
        float w = tagW > 0 ? tagW : TAG_PLACEHOLDER_W;
        r.put(Part.NAME_TAG, new Rect((guiW - w) / 2f, StreamNameTag.TOP_OFFSET, w, tagH));
        r.put(Part.CHAT, new Rect(0, guiH - CHAT_BOTTOM - chatH, chatW + 2 * CHAT_PAD, chatH));
        r.put(Part.GUI, new Rect((guiW - HUD_W) / 2f, guiH - HUD_H, HUD_W, HUD_H));
        return new StreamPreviewRegions(r);
    }

    public Rect rect(Part part) {
        return rects.get(part);
    }

    /** The part under ({@code x}, {@code y}) in GUI pixels, or null for none. */
    @Nullable
    public Part pick(float x, float y) {
        for (Part part : Part.values()) {
            if (rects.get(part).contains(x, y)) return part;
        }
        return null;
    }
}
