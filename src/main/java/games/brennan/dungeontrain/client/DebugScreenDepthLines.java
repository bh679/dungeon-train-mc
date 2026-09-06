package games.brennan.dungeontrain.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the Y figures in the debug screen's left column so a dimensional carriage reports the
 * depth it is pretending to be at.
 *
 * <p><b>Why the text and not the source.</b> The obvious implementation redirects
 * {@code BlockPos.getY()} inside {@code DebugScreenOverlay.getGameInformation}, and it is wrong: the
 * same {@code BlockPos} is handed to the biome, light-level and chunk lookups further down that
 * method, so shifting it would have F3 report a different biome and a different light level as well
 * — and would go looking for a chunk section that may not be loaded. Only the printed numbers are
 * meant to move, so only the printed numbers are touched.</p>
 *
 * <p><b>Every rewrite is opt-in and reversible.</b> Each line is matched against the exact shape
 * vanilla prints and passed through untouched when it does not match, so a formatting change in a
 * future Minecraft degrades to "the debug screen tells the truth" rather than to a mangled line or a
 * crash. Nothing here parses a number it did not first match as one.</p>
 *
 * <p>No Minecraft imports, so it unit-tests without a NeoForge bootstrap — the same convention
 * {@link ClientPortalRoomFog} follows.</p>
 */
public final class DebugScreenDepthLines {

    /** {@code XYZ: 12.345 / -103.00000 / 67.890} — the camera's exact position. */
    private static final Pattern XYZ =
        Pattern.compile("^(XYZ: )(-?[0-9.]+)( / )(-?[0-9.]+)( / )(-?[0-9.]+)$");

    /** {@code Block: 12 -103 67 [12 5 3]} — world block position, then its position in its section. */
    private static final Pattern BLOCK =
        Pattern.compile("^(Block: )(-?\\d+)( )(-?\\d+)( )(-?\\d+)( \\[)(-?\\d+)( )(-?\\d+)( )(-?\\d+)(\\])$");

    /** {@code Chunk: 0 -7 4 [0 4 in r.0.0.mca]} — the middle figure is the section index. */
    private static final Pattern CHUNK =
        Pattern.compile("^(Chunk: )(-?\\d+)( )(-?\\d+)( )(-?\\d+)( \\[.*)$");

    /** {@code Chunk-relative: 12 5 3} — all the reduced-info debug screen prints. */
    private static final Pattern CHUNK_RELATIVE =
        Pattern.compile("^(Chunk-relative: )(-?\\d+)( )(-?\\d+)( )(-?\\d+)$");

    /**
     * {@code Targeted Block: 12, -103, 67} — and the fluid line, which prints the same way. The
     * leading group swallows the underline formatting code vanilla puts in front of the label.
     */
    private static final Pattern TARGETED =
        Pattern.compile("^(.*Targeted (?:Block|Fluid): )(-?\\d+)(, )(-?\\d+)(, )(-?\\d+)$");

    /** Blocks per chunk section, for the two lines that print a position within one. */
    private static final int SECTION = 16;

    private DebugScreenDepthLines() {}

    /**
     * The debug screen's lines with every Y figure moved by {@code yShift}.
     *
     * <p>Returns {@code lines} itself when there is nothing to do, so the ordinary case — which is
     * every frame the player is not in a dimensional carriage — allocates nothing.</p>
     *
     * @param blockY the camera's real block Y, which the {@code Chunk} and {@code Chunk-relative}
     *               lines need because they print a <i>derived</i> figure rather than the Y itself
     */
    public static List<String> shifted(List<String> lines, int blockY, int yShift) {
        if (lines == null || yShift == 0) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(shift(line, blockY, yShift));
        return out;
    }

    /** One line, rewritten if it is one of the shapes that carries a Y and left alone if it is not. */
    static String shift(String line, int blockY, int yShift) {
        if (line == null || line.isEmpty() || yShift == 0) return line;

        Matcher xyz = XYZ.matcher(line);
        if (xyz.matches()) {
            double y = parseDouble(xyz.group(4));
            return Double.isNaN(y) ? line
                : xyz.group(1) + xyz.group(2) + xyz.group(3)
                    + String.format(Locale.ROOT, "%.5f", y + yShift)
                    + xyz.group(5) + xyz.group(6);
        }

        Matcher block = BLOCK.matcher(line);
        if (block.matches()) {
            long y = parseLong(block.group(4));
            if (y == Long.MIN_VALUE) return line;
            long moved = y + yShift;
            return block.group(1) + block.group(2) + block.group(3) + moved
                + block.group(5) + block.group(6) + block.group(7)
                + block.group(8) + block.group(9) + Math.floorMod(moved, SECTION)
                + block.group(11) + block.group(12) + block.group(13);
        }

        Matcher chunk = CHUNK.matcher(line);
        if (chunk.matches()) {
            // The section index, not a Y — recomputed from the shifted block position rather than
            // shifted itself, or a shift that is not a whole number of sections would drift.
            return chunk.group(1) + chunk.group(2) + chunk.group(3)
                + Math.floorDiv((long) blockY + yShift, SECTION)
                + chunk.group(5) + chunk.group(6) + chunk.group(7);
        }

        Matcher relative = CHUNK_RELATIVE.matcher(line);
        if (relative.matches()) {
            return relative.group(1) + relative.group(2) + relative.group(3)
                + Math.floorMod((long) blockY + yShift, SECTION)
                + relative.group(5) + relative.group(6);
        }

        Matcher targeted = TARGETED.matcher(line);
        if (targeted.matches()) {
            long y = parseLong(targeted.group(4));
            return y == Long.MIN_VALUE ? line
                : targeted.group(1) + targeted.group(2) + targeted.group(3) + (y + yShift)
                    + targeted.group(5) + targeted.group(6);
        }

        return line;
    }

    /** {@link Long#MIN_VALUE} for "not a number", which is not a Y any world can hold. */
    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /** {@link Double#NaN} for "not a number" — the regex admits {@code "1.2.3"}, which this rejects. */
    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
