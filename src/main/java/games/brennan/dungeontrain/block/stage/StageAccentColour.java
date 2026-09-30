package games.brennan.dungeontrain.block.stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The dye colours a stage's glass and glazed-terracotta placeholders take, picked from its three
 * concrete colour slots (primary, secondary, background) rather than stored separately.
 *
 * <p>The <b>accent</b> is the first concrete slot, in slot order, whose colour is not a neutral
 * (white, light grey, grey, black) — a neutral glass reads as no colour at all. When all three are
 * neutral the background slot's colour is used as-is. The <b>second accent</b> is the next
 * non-neutral slot after the accent, else the accent again.</p>
 */
public final class StageAccentColour {

    private static final Set<String> NEUTRALS = Set.of("white", "light_gray", "gray", "black");
    private static final String CONCRETE_SUFFIX = "_concrete";
    private static final String FALLBACK = "white";

    private StageAccentColour() {}

    /**
     * Accent colour {@code index} (0 = accent, 1 = second accent) for these concrete block ids, as
     * a dye name ({@code "light_blue"}).
     */
    public static String accent(List<String> concrete, int index) {
        List<String> colours = new ArrayList<>();
        for (String id : concrete) colours.add(dyeOf(id));
        List<String> vivid = new ArrayList<>();
        for (String c : colours) {
            if (!NEUTRALS.contains(c) && !vivid.contains(c)) vivid.add(c);
        }
        if (vivid.isEmpty()) {
            return colours.isEmpty() ? FALLBACK : colours.get(colours.size() - 1);
        }
        return vivid.get(Math.min(Math.max(0, index), vivid.size() - 1));
    }

    /** {@code minecraft:light_blue_concrete} → {@code light_blue}; anything else → white. */
    static String dyeOf(String concreteId) {
        if (concreteId == null) return FALLBACK;
        String path = concreteId.substring(concreteId.indexOf(':') + 1);
        if (!path.endsWith(CONCRETE_SUFFIX) || path.length() == CONCRETE_SUFFIX.length()) return FALLBACK;
        return path.substring(0, path.length() - CONCRETE_SUFFIX.length());
    }
}
