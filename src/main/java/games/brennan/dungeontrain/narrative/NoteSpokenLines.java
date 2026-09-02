package games.brennan.dungeontrain.narrative;

import java.util.ArrayList;
import java.util.List;

/**
 * The script a note echo reads aloud: the lines the author actually wrote in their Death Note or
 * Love Note, plus the pacing that makes them land as speech rather than a wall of chat.
 *
 * <p>Pure and Minecraft-free, like {@link DeathNoteTitle} — the same rules run on the author's
 * machine (at signing, to decide what is uploaded), on the relay's terms (the caps here match
 * {@code deathnotes.js} {@code MAX_BODY_LINES} / {@code MAX_BODY_LINE_CHARS}) and on the target's
 * machine (to pace the delivery), so all three agree without a server.</p>
 *
 * <p><b>The first line of page 1 is never spoken.</b> It is the target's name — that is how a note
 * names its victim ({@link DeathNoteTitle#firstLineTarget}) — and the echo opens by calling that
 * name out itself ({@code @Player}), so repeating it as a line of the note would say it twice.</p>
 */
public final class NoteSpokenLines {

    /** Most lines an echo will ever speak. A note is a page of handwriting, not a monologue. */
    public static final int MAX_LINES = 16;
    /** Longest single spoken line; a chat line, so roughly one screen's width of text. */
    public static final int MAX_LINE_CHARS = 128;

    /**
     * Ticks of silence bought per character of the line just spoken — 2.5 ticks/char is about 8
     * characters a second, slower than reading aloud because the reader is also being read: the
     * next line should not arrive until the last one has been taken in. Longer lines therefore hang
     * longer before the next one, which is the whole point: the note is being read out, not dumped.
     */
    private static final float TICKS_PER_CHAR = 2.5f;
    /**
     * Never less than three seconds between lines, however short the line. The floor does most of
     * the work: real note lines are short ("I remember."), so without a generous floor the whole
     * note lands almost at once and reads as a paste rather than someone speaking. It also has to
     * clear the arrival broadcast that lands immediately before the first line.
     */
    public static final int MIN_DELAY_TICKS = 60;
    /** Never more than twelve seconds, however long the line. */
    public static final int MAX_DELAY_TICKS = 240;

    /**
     * Lines of note per carriage of run-up: a longer note starts being read from further away, so it
     * has room to finish around the time the echo actually reaches its target rather than still
     * being halfway through the page.
     *
     * <p>Measured in CARRIAGES rather than blocks on purpose. The echo is spawned into its
     * carriage's shipyard coordinates while the target stands in world space, so the distance
     * between them is not a subtraction — carriage indices are the one frame both share (see
     * {@code DeathNoteEchoController}). A carriage is roughly nine to thirteen blocks, so this is
     * "about five blocks per line" at the only resolution actually available.</p>
     */
    private static final int LINES_PER_CARRIAGE_OF_LEAD = 2;

    private NoteSpokenLines() {}

    /**
     * How many carriages apart the echo may be and still be reading: one per
     * {@link #LINES_PER_CARRIAGE_OF_LEAD} lines, and never less than the adjacent-carriage gap that
     * every note got before length mattered. A note of one or two lines therefore behaves exactly as
     * it did; a four-line note starts a carriage earlier; an eight-line note four.
     *
     * <p>The same gap governs stopping: walk far enough ahead and the echo falls silent, and resumes
     * where it left off once you are back inside it.</p>
     */
    public static int startCarriageGap(int lineCount) {
        if (lineCount <= 0) return 1;
        return Math.max(1, (lineCount + LINES_PER_CARRIAGE_OF_LEAD - 1) / LINES_PER_CARRIAGE_OF_LEAD);
    }

    /**
     * The spoken script for a note whose book holds {@code pages}: every page split into lines,
     * trimmed, blanks dropped, the target-name line (the first non-blank line of page 1) dropped,
     * then capped to {@link #MAX_LINES} lines of {@link #MAX_LINE_CHARS} characters.
     *
     * <p>Returns an empty list for a note that is only a name — which is most of them, and a
     * perfectly good note: the echo then arrives silently, exactly as echoes did before it could
     * speak at all.</p>
     */
    public static List<String> fromPages(List<String> pages) {
        List<String> out = new ArrayList<>();
        if (pages == null) return out;
        boolean targetLineDropped = false;
        for (String page : pages) {
            if (page == null) continue;
            for (String raw : page.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (!targetLineDropped) {           // the victim's name — spoken as @name, not as a line
                    targetLineDropped = true;
                    continue;
                }
                out.add(clamp(line));
                if (out.size() >= MAX_LINES) return out;
            }
        }
        return out;
    }

    /**
     * How long to wait after speaking {@code line} before the next one: proportional to its length
     * ({@link #TICKS_PER_CHAR}) and clamped to {@link #MIN_DELAY_TICKS}..{@link #MAX_DELAY_TICKS}.
     * A null/blank line still buys the minimum pause, so a caller can pace the opening {@code @name}
     * with the same call.
     */
    public static int delayTicksFor(String line) {
        int chars = line == null ? 0 : line.length();
        int ticks = Math.round(chars * TICKS_PER_CHAR);
        if (ticks < MIN_DELAY_TICKS) return MIN_DELAY_TICKS;
        return Math.min(ticks, MAX_DELAY_TICKS);
    }

    /** Truncate one line to {@link #MAX_LINE_CHARS} without splitting a surrogate pair. */
    private static String clamp(String line) {
        if (line.length() <= MAX_LINE_CHARS) return line;
        int end = Character.isHighSurrogate(line.charAt(MAX_LINE_CHARS - 1))
                ? MAX_LINE_CHARS - 1 : MAX_LINE_CHARS;
        return line.substring(0, end).trim();
    }
}
