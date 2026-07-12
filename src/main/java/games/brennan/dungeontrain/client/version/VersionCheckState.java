package games.brennan.dungeontrain.client.version;

/**
 * Session-scoped holder for the result of "is this installed mod up to date
 * with the latest GitHub release?". One JVM, one successful fetch — repeat
 * calls to {@link #ensureChecked()} are idempotent unless the previous
 * attempt ended in {@link Status#ERROR}, in which case clicking the status
 * widget retries.
 *
 * <p>All writes happen on the single-thread daemon executor inside
 * {@link GitHubLatestReleaseFetcher}; the render thread only reads the
 * {@code volatile} fields, so no further synchronisation is required.</p>
 */
public final class VersionCheckState {

    public enum Status { CHECKING, LATEST, UPDATE_AVAILABLE, AHEAD, ERROR }

    private static volatile Status status = Status.CHECKING;
    private static volatile String latestTag;
    private static volatile boolean attempted;

    private VersionCheckState() {}

    public static void ensureChecked() {
        if (!attempted || status == Status.ERROR) {
            attempted = true;
            status = Status.CHECKING;
            latestTag = null;
            GitHubLatestReleaseFetcher.fetchAsync();
        }
    }

    static void accept(Status next, String tag) {
        latestTag = tag;
        status = next;
    }

    public static Status status() { return status; }
    public static String latestTag() { return latestTag; }
}
