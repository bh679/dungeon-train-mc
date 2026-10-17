package games.brennan.dungeontrain.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the coordinate figures on the debug screen so a dimensional carriage reports the depth it
 * is pretending to be at, with its room's centre as X 0, Z 0.
 *
 * <p><b>Why the text and not the source.</b> The obvious implementation redirects
 * {@code BlockPos.getX/getY/getZ()} inside {@code DebugScreenOverlay.getGameInformation}, and it is wrong: the
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

    /** Blocks to add to each displayed axis. {@link #NONE} is "tell the truth". */
    public record Shift(int x, int y, int z) {
        public static final Shift NONE = new Shift(0, 0, 0);

        public boolean isZero() {
            return x == 0 && y == 0 && z == 0;
        }

        boolean movesXZ() {
            return x != 0 || z != 0;
        }
    }

    /** {@code XYZ: 12.345 / -103.00000 / 67.890}: the camera's exact position. */
    private static final Pattern XYZ =
        Pattern.compile("^(XYZ: )(-?[0-9.]+)( / )(-?[0-9.]+)( / )(-?[0-9.]+)$");

    /** {@code Block: 12 -103 67 [12 5 3]}: world block position, then its position in its section. */
    private static final Pattern BLOCK =
        Pattern.compile("^(Block: )(-?\\d+) (-?\\d+) (-?\\d+) \\[(-?\\d+) (-?\\d+) (-?\\d+)\\]$");

    /**
     * {@code Chunk: 0 -7 4 [0 4 in r.0.0.mca]}: chunk X, section index, chunk Z, then the block's
     * X and Z within its chunk and the region file. Every figure is derived, so all are recomputed.
     */
    private static final Pattern CHUNK = Pattern.compile(
        "^Chunk: (-?\\d+) (-?\\d+) (-?\\d+) \\[(-?\\d+) (-?\\d+) in r\\.(-?\\d+)\\.(-?\\d+)\\.mca\\]$");

    /** Any other {@code Chunk:} line: only its section index is understood, and only Y may move it. */
    private static final Pattern CHUNK_LOOSE =
        Pattern.compile("^(Chunk: )(-?\\d+)( )(-?\\d+)( )(-?\\d+)( \\[.*)$");

    /** {@code Chunk-relative: 12 5 3}: all the reduced-info debug screen prints. */
    private static final Pattern CHUNK_RELATIVE =
        Pattern.compile("^(Chunk-relative: )(-?\\d+) (-?\\d+) (-?\\d+)$");

    /**
     * {@code Targeted Block: 12, -103, 67}, and the fluid line, which prints the same way. The
     * leading group swallows the underline formatting code vanilla puts in front of the label.
     */
    private static final Pattern TARGETED =
        Pattern.compile("^(.*Targeted (?:Block|Fluid): )(-?\\d+), (-?\\d+), (-?\\d+)$");

    /** Blocks per chunk section, for the lines that print a position within one. */
    private static final int SECTION = 16;
    /** Chunks per region file, for the region name the Chunk line prints. */
    private static final int REGION = 32;

    private DebugScreenDepthLines() {}

    /**
     * The debug screen's lines with every coordinate figure moved by {@code shift}.
     *
     * <p>Returns {@code lines} itself when there is nothing to do, so the ordinary case (every
     * frame the player is not in a dimensional carriage) allocates nothing.</p>
     *
     * @param blockX the camera's real block X; with {@code blockY} and {@code blockZ}, what the
     *               {@code Chunk} and {@code Chunk-relative} lines are recomputed from, since they print
     *               <i>derived</i> figures rather than the position itself
     */
    public static List<String> shifted(List<String> lines, int blockX, int blockY, int blockZ,
                                       Shift shift) {
        if (lines == null || shift == null || shift.isZero()) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(shift(line, blockX, blockY, blockZ, shift));
        return out;
    }

    /** One line, rewritten if it is one of the shapes that carries a position and left alone if not. */
    static String shift(String line, int blockX, int blockY, int blockZ, Shift shift) {
        if (line == null || line.isEmpty() || shift == null || shift.isZero()) return line;

        Matcher xyz = XYZ.matcher(line);
        if (xyz.matches()) {
            double x = parseDouble(xyz.group(2));
            double y = parseDouble(xyz.group(4));
            double z = parseDouble(xyz.group(6));
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return line;
            return xyz.group(1) + String.format(Locale.ROOT, "%.3f", x + shift.x())
                + xyz.group(3) + String.format(Locale.ROOT, "%.5f", y + shift.y())
                + xyz.group(5) + String.format(Locale.ROOT, "%.3f", z + shift.z());
        }

        Matcher block = BLOCK.matcher(line);
        if (block.matches()) {
            long x = parseLong(block.group(2));
            long y = parseLong(block.group(3));
            long z = parseLong(block.group(4));
            if (x == Long.MIN_VALUE || y == Long.MIN_VALUE || z == Long.MIN_VALUE) return line;
            long mx = x + shift.x(), my = y + shift.y(), mz = z + shift.z();
            return "Block: " + mx + " " + my + " " + mz + " ["
                + Math.floorMod(mx, SECTION) + " " + Math.floorMod(my, SECTION) + " "
                + Math.floorMod(mz, SECTION) + "]";
        }

        if (CHUNK.matcher(line).matches()) {
            // Every figure here is derived from the block position, so it is rebuilt from the shifted
            // one rather than shifted itself; a shift that is not a whole chunk would drift otherwise.
            long x = (long) blockX + shift.x(), y = (long) blockY + shift.y(), z = (long) blockZ + shift.z();
            long cx = Math.floorDiv(x, SECTION), cz = Math.floorDiv(z, SECTION);
            return "Chunk: " + cx + " " + Math.floorDiv(y, SECTION) + " " + cz + " ["
                + Math.floorMod(x, SECTION) + " " + Math.floorMod(z, SECTION) + " in r."
                + Math.floorDiv(cx, REGION) + "." + Math.floorDiv(cz, REGION) + ".mca]";
        }

        Matcher chunk = CHUNK_LOOSE.matcher(line);
        if (chunk.matches()) {
            // A shape this does not fully know: its bracket may carry X/Z figures that cannot be
            // moved safely, so it tells the truth unless the shift is Y alone.
            if (shift.movesXZ()) return line;
            return chunk.group(1) + chunk.group(2) + chunk.group(3)
                + Math.floorDiv((long) blockY + shift.y(), SECTION)
                + chunk.group(5) + chunk.group(6) + chunk.group(7);
        }

        Matcher relative = CHUNK_RELATIVE.matcher(line);
        if (relative.matches()) {
            return relative.group(1)
                + Math.floorMod((long) blockX + shift.x(), SECTION) + " "
                + Math.floorMod((long) blockY + shift.y(), SECTION) + " "
                + Math.floorMod((long) blockZ + shift.z(), SECTION);
        }

        Matcher targeted = TARGETED.matcher(line);
        if (targeted.matches()) {
            long x = parseLong(targeted.group(2));
            long y = parseLong(targeted.group(3));
            long z = parseLong(targeted.group(4));
            if (x == Long.MIN_VALUE || y == Long.MIN_VALUE || z == Long.MIN_VALUE) return line;
            return targeted.group(1) + (x + shift.x()) + ", " + (y + shift.y()) + ", " + (z + shift.z());
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
