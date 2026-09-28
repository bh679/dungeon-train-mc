package games.brennan.dungeontrain.portal;

/**
 * The switch behind {@code /dungeontrain debug puppet-trace}: per-tick coordinates for every puppet
 * the server describes, and per-frame resolution of the nearest one on the client.
 *
 * <p>One static so that in a single-player world — the integrated server and the client in one
 * JVM — the command arms both ends at once. On a dedicated server the client half never sees it
 * flip, which is fine: the server lines are the ones that say whether the coordinates leaving the
 * server hold still, and that is the question this exists to answer.</p>
 */
public final class PortalPuppetTrace {

    private static volatile boolean ENABLED = false;

    private PortalPuppetTrace() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }
}
