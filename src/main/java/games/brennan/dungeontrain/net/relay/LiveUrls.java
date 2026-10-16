package games.brennan.dungeontrain.net.relay;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which relay-supplied URLs the Live Feed will fetch or PUT to: https anywhere, plain http only on
 * this machine (a local relay with a stand-in bucket). Anything else — another scheme, plain http to
 * a public host, a malformed URL — is refused, so a bad status or presign reply cannot point a
 * viewer's ffmpeg or the uploader somewhere else.
 */
public final class LiveUrls {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    private LiveUrls() {}

    /** {@code url} may be fetched: https, or http on loopback, and (when non-empty) ends in {@code suffix}. */
    public static boolean allowed(@Nullable String url, String suffix) {
        if (url == null || url.isEmpty() || !url.endsWith(suffix)) return false;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) return false;
        if (scheme.equals("https")) return true;
        return scheme.equals("http") && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
    }

    /** {@code url} when {@link #allowed}, else null. */
    @Nullable
    public static String orNull(@Nullable String url, String suffix) {
        return allowed(url, suffix) ? url : null;
    }
}
