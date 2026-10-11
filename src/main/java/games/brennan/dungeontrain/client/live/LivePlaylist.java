package games.brennan.dungeontrain.client.live;

import java.util.ArrayList;
import java.util.List;

/**
 * The little of HLS the uploader needs: which segment files a {@code live.m3u8} lists, and whether
 * it is finished. Pure, so it is unit-tested without a game.
 */
public final class LivePlaylist {

    public static final String FILE_NAME = "live.m3u8";

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
}
