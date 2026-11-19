package games.brennan.dungeontrain.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the debug screen's dimension line, {@code minecraft:overworld FC: 0}, so it names a band.
 *
 * <p>The printed text is what changes, never the level: everything vanilla looks up from the dimension
 * has already happened by the time these lines exist. As with {@link DebugScreenDepthLines}, a line
 * that is not the exact shape vanilla prints passes through untouched, so a formatting change in a
 * future Minecraft degrades to "the debug screen tells the truth".</p>
 *
 * <p>No Minecraft imports, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class DebugScreenDimensionLine {

    private static final String FORCED_CHUNKS = " FC: ";

    private DebugScreenDimensionLine() {}

    /**
     * {@code lines} with {@code realId}'s dimension line reading {@code shownId} instead. Returns
     * {@code lines} itself when there is nothing to change, so a frame with no rename allocates nothing.
     */
    public static List<String> renamed(List<String> lines, String realId, String shownId) {
        if (lines == null || realId == null || shownId == null || shownId.isEmpty() || shownId.equals(realId)) {
            return lines;
        }
        String prefix = realId + FORCED_CHUNKS;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || !line.startsWith(prefix)) continue;
            String count = line.substring(prefix.length());
            if (!isCount(count)) continue;
            List<String> out = new ArrayList<>(lines);
            out.set(i, shownId + FORCED_CHUNKS + count);
            return out;
        }
        return lines;
    }

    private static boolean isCount(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
