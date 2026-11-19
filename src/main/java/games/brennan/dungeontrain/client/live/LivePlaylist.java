package games.brennan.dungeontrain.client.live;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;

/**
 * The little of HLS the uploader needs: which segment files a {@code live.m3u8} lists, its media
 * sequence, whether it is finished, and the copy of it that is safe to publish. Pure apart from
 * {@link #dropEndlist}, so it is unit-tested without a game.
 */
public final class LivePlaylist {

    public static final String FILE_NAME = "live.m3u8";

    private static final String MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:";
    private static final String DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE:";
    private static final String DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String ENDLIST = "#EXT-X-ENDLIST";

    private final List<String> segments;
    private final long mediaSequence;
    private final boolean ended;

    private LivePlaylist(List<String> segments, long mediaSequence, boolean ended) {
        this.segments = List.copyOf(segments);
        this.mediaSequence = mediaSequence;
        this.ended = ended;
    }

    /** Segment file names in playlist order (relative names only; anything with a path is skipped). */
    public List<String> segments() {
        return segments;
    }

    /** Sequence number of the first segment; segment {@code i} is {@code mediaSequence() + i}. */
    public long mediaSequence() {
        return mediaSequence;
    }

    /** True once ffmpeg wrote {@code #EXT-X-ENDLIST}: the stream is over. */
    public boolean ended() {
        return ended;
    }

    public static LivePlaylist parse(String text) {
        List<String> segs = new ArrayList<>();
        boolean ended = false;
        long seq = 0;
        if (text == null) return new LivePlaylist(segs, 0, false);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith(ENDLIST)) ended = true;
                if (line.startsWith(MEDIA_SEQUENCE)) seq = parseLong(line.substring(MEDIA_SEQUENCE.length()), 0);
                continue;
            }
            if (!isSafeName(line)) continue;
            segs.add(line);
        }
        return new LivePlaylist(segs, seq, ended);
    }

    /**
     * Sequence numbers of the segments ffmpeg marked {@code #EXT-X-DISCONTINUITY} in {@code text}: where an
     * encoder restarted at a new bitrate picked the stream up ({@link LiveEncoder.Resume}). The uploader keeps
     * them, so {@link #publishable} can still count them once they slide out of the window.
     */
    public static List<Long> discontinuities(String text) {
        List<Long> out = new ArrayList<>();
        if (text == null) return out;
        long sn = parse(text).mediaSequence();
        boolean marked = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.equals(DISCONTINUITY)) marked = true;
                continue;
            }
            if (!isSafeName(line)) {
                marked = false;
                continue;
            }
            if (marked) out.add(sn);
            sn++;
            marked = false;
        }
        return out;
    }

    /** {@code text} without its {@code #EXT-X-ENDLIST}: the stream goes on. */
    public static String withoutEndlist(String text) {
        if (text == null || !text.contains(ENDLIST)) return text;
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().startsWith(ENDLIST)) kept.add(line);
        }
        return String.join("\n", kept) + "\n";
    }

    /**
     * Takes the ENDLIST out of the local playlist an encoder left when it was swapped for another
     * mid-stream, so its successor appends to a stream that is still going (and the uploader never
     * publishes the end). Written to a temp file and moved over, as ffmpeg itself does. Missing file:
     * nothing to do. Blocking I/O: never on the render thread.
     */
    public static void dropEndlist(Path playlist) {
        try {
            if (!Files.exists(playlist)) return;
            String text = Files.readString(playlist, StandardCharsets.UTF_8);
            String kept = withoutEndlist(text);
            if (kept.equals(text)) return;
            Path tmp = playlist.resolveSibling(playlist.getFileName() + ".resume");
            Files.writeString(tmp, kept, StandardCharsets.UTF_8);
            Files.move(tmp, playlist, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Optional<String> publishable(String text, Set<String> uploaded, NavigableSet<Long> skippedSeqs) {
        return publishable(text, uploaded, skippedSeqs, Collections.emptyNavigableSet());
    }

    /**
     * The playlist to put on storage, built from ffmpeg's {@code text}, so that it never names a segment
     * that is not there. A segment still being retried (neither {@code uploaded} nor given up) cuts the
     * playlist off before it, so nothing after it is announced yet; a given-up segment (its sequence
     * number in {@code skippedSeqs}) is dropped and the next one is marked {@code #EXT-X-DISCONTINUITY}.
     *
     * <p>Sequence numbers stay stable across refreshes of a sliding window: a kept segment {@code sn}
     * is always published as {@code sn − (given-up segments below sn)}, and every inserted
     * discontinuity that has slid out of the window is counted into
     * {@code #EXT-X-DISCONTINUITY-SEQUENCE}, as is every encoder restart ({@code encoderBreaks}, from
     * {@link #discontinuities}) that has, since ffmpeg itself never counts them. {@code #EXT-X-ENDLIST}
     * only goes out when nothing was cut off. Empty when the result would name no segment.</p>
     */
    public static Optional<String> publishable(String text, Set<String> uploaded, NavigableSet<Long> skippedSeqs,
                                               NavigableSet<Long> encoderBreaks) {
        if (text == null) return Optional.empty();
        long mediaSeq = parse(text).mediaSequence();
        long skippedBelow = skippedSeqs.headSet(mediaSeq, false).size();
        long insertedBelow = skippedSeqs.headSet(mediaSeq - 1, false).stream()
            .filter(s -> !skippedSeqs.contains(s + 1)).count()
            // A break on a dropped segment, or right after one, is the inserted discontinuity already counted.
            + encoderBreaks.headSet(mediaSeq, false).stream()
            .filter(b -> !skippedSeqs.contains(b) && !skippedSeqs.contains(b - 1)).count();

        List<String> out = new ArrayList<>();
        List<String> block = new ArrayList<>();
        boolean cut = false;
        int kept = 0;
        long sn = mediaSeq;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith(MEDIA_SEQUENCE)) {
                    out.add(MEDIA_SEQUENCE + (mediaSeq - skippedBelow));
                    if (insertedBelow > 0 && !text.contains(DISCONTINUITY_SEQUENCE)) {
                        out.add(DISCONTINUITY_SEQUENCE + insertedBelow);
                    }
                } else if (line.startsWith(DISCONTINUITY_SEQUENCE)) {
                    out.add(DISCONTINUITY_SEQUENCE + (parseLong(line.substring(DISCONTINUITY_SEQUENCE.length()), 0) + insertedBelow));
                } else if (line.startsWith(ENDLIST)) {
                    if (!cut) out.add(line);
                } else if (isSegmentTag(line)) {
                    block.add(line);
                } else if (!cut) {
                    out.add(line);
                }
                continue;
            }
            if (!isSafeName(line)) {
                block.clear();
                continue;
            }
            long thisSn = sn++;
            if (cut || skippedSeqs.contains(thisSn) || !uploaded.contains(line)) {
                // Still being retried: announce nothing from here on. Given up: drop it alone.
                if (!skippedSeqs.contains(thisSn)) cut = true;
                block.clear();
                continue;
            }
            if (skippedSeqs.contains(thisSn - 1) && !block.contains(DISCONTINUITY)) out.add(DISCONTINUITY);
            out.addAll(block);
            block.clear();
            out.add(line);
            kept++;
        }
        if (kept == 0) return Optional.empty();
        return Optional.of(String.join("\n", out) + "\n");
    }

    private static boolean isSegmentTag(String line) {
        return line.startsWith("#EXTINF") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")
            || line.startsWith("#EXT-X-BYTERANGE") || line.equals(DISCONTINUITY);
    }

    private static boolean isSafeName(String line) {
        return !line.contains("/") && !line.contains("\\") && !line.contains("..");
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
