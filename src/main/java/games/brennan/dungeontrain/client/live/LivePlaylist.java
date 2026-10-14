package games.brennan.dungeontrain.client.live;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The little of HLS the uploader needs: which segment files a {@code live.m3u8} lists, and whether
 * it is finished. Pure, so it is unit-tested without a game.
 */
public final class LivePlaylist {

    public static final String FILE_NAME = "live.m3u8";
    private static final String MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:";
    private static final String ENDLIST = "#EXT-X-ENDLIST";
    /** Tags that describe the segment line after them, so travel (and are dropped) with it. */
    private static final List<String> SEGMENT_TAGS =
        List.of("#EXTINF", "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-BYTERANGE");

    private final List<String> segments;
    private final boolean ended;

    private LivePlaylist(List<String> segments, boolean ended) {
        this.segments = List.copyOf(segments);
        this.ended = ended;
    }

    /** Segment file names in playlist order (relative names only; anything with a path is skipped). */
    public List<String> segments() {
        return segments;
    }

    /** True once ffmpeg wrote {@code #EXT-X-ENDLIST}: the stream is over. */
    public boolean ended() {
        return ended;
    }

    public static LivePlaylist parse(String text) {
        List<String> segs = new ArrayList<>();
        boolean ended = false;
        if (text == null) return new LivePlaylist(segs, false);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-ENDLIST")) ended = true;
                continue;
            }
            if (line.contains("/") || line.contains("\\") || line.contains("..")) continue;
            segs.add(line);
        }
        return new LivePlaylist(segs, ended);
    }

    /**
     * The playlist as it may be published, given which segments are in storage: it never names a file
     * a viewer would 404 on. A segment that will not be uploaded ({@code givenUp}: failed for good, or
     * gone) cuts the window — only what follows it is listed, and the media sequence is raised by what
     * was dropped so every segment keeps its number. A segment still being retried cuts the tail, along
     * with the ENDLIST. {@code text} unchanged when everything is up; null when nothing can be listed.
     */
    @Nullable
    public static String publishable(String text, Predicate<String> uploaded, Predicate<String> givenUp) {
        List<String> header = new ArrayList<>();
        List<List<String>> groups = new ArrayList<>(); // each: the segment's tags, then its name
        List<String> names = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        boolean ended = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith(ENDLIST)) {
                ended = true;
            } else if (!line.startsWith("#")) {
                tags.add(line);
                groups.add(tags);
                names.add(line);
                tags = new ArrayList<>();
            } else if (names.isEmpty() && tags.isEmpty() && SEGMENT_TAGS.stream().noneMatch(line::startsWith)) {
                header.add(line);
            } else {
                tags.add(line);
            }
        }
        int start = 0;
        for (int i = 0; i < names.size(); i++) {
            if (!uploaded.test(names.get(i)) && givenUp.test(names.get(i))) start = i + 1;
        }
        int end = start;
        while (end < names.size() && uploaded.test(names.get(end))) end++;
        if (start == 0 && end == names.size()) return text;
        if (start == end) return null;

        StringBuilder out = new StringBuilder();
        for (String line : header) {
            out.append(line.startsWith(MEDIA_SEQUENCE) ? raiseSequence(line, start) : line).append('\n');
        }
        // No sequence tag means 0; a cut head still has to say where it starts.
        if (start > 0 && header.stream().noneMatch(l -> l.startsWith(MEDIA_SEQUENCE))) {
            out.append(MEDIA_SEQUENCE).append(start).append('\n');
        }
        for (List<String> group : groups.subList(start, end)) {
            for (String line : group) out.append(line).append('\n');
        }
        if (end == names.size()) {
            for (String line : tags) out.append(line).append('\n');
            if (ended) out.append(ENDLIST).append('\n');
        }
        return out.toString();
    }

    private static String raiseSequence(String line, int by) {
        try {
            long seq = Long.parseLong(line.substring(MEDIA_SEQUENCE.length()).trim());
            return MEDIA_SEQUENCE + (seq + by);
        } catch (NumberFormatException e) {
            return line;
        }
    }
}
