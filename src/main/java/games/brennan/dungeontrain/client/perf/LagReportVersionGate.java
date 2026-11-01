package games.brennan.dungeontrain.client.perf;

import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.version.SemverCompare;
import games.brennan.dungeontrain.client.version.VersionCheckState;

/**
 * Whether this build is new enough for {@link ClientStallWatchdog} and {@link LagReportPrompt} to
 * run: the newest release, a build ahead of it (dev), or at most {@link #MAX_MINORS_BEHIND} MINOR
 * versions behind. A freeze on an older build has most likely been fixed since, so neither its
 * stack dump nor its report is worth the log space.
 *
 * <p>"Newest" is {@link VersionCheckState}: the GitHub latest-release check the title screen already
 * makes every session, so the gate costs no request of its own. Until that check answers (or when it
 * fails, offline), the build is not known to be recent and lag detection stays off.</p>
 */
public final class LagReportVersionGate {

    /** How many MINOR releases behind the newest a build may be and still run lag detection. */
    static final int MAX_MINORS_BEHIND = 20;

    private LagReportVersionGate() {}

    /** Whether lag detection may run now. Any thread (the state's fields are volatile). */
    static boolean enabled() {
        VersionCheckState.Status status = VersionCheckState.status();
        if (status == VersionCheckState.Status.LATEST || status == VersionCheckState.Status.AHEAD) return true;
        return status == VersionCheckState.Status.UPDATE_AVAILABLE
            && isRecent(VersionInfo.VERSION, VersionCheckState.latestTag());
    }

    /**
     * True once the check has answered and this build is too old: final for the session, since the
     * installed jar cannot change while it runs.
     */
    static boolean ruledOut() {
        return VersionCheckState.status() == VersionCheckState.Status.UPDATE_AVAILABLE
            && !isRecent(VersionInfo.VERSION, VersionCheckState.latestTag());
    }

    /**
     * Whether {@code installed} is at or ahead of {@code latest}, or the same MAJOR and at most
     * {@link #MAX_MINORS_BEHIND} MINORs behind. PATCH, a leading {@code v}, {@code -pre} and
     * {@code +build} are ignored. False when either cannot be read. Pure, unit-tested.
     */
    static boolean isRecent(String installed, String latest) {
        int[] have = majorMinor(installed);
        int[] newest = majorMinor(latest);
        if (have == null || newest == null) return false;
        if (have[0] != newest[0]) return have[0] > newest[0];
        return newest[1] - have[1] <= MAX_MINORS_BEHIND;
    }

    /** {@code {major, minor}}, or {@code null} when the text is not {@code digits.digits[...]}. */
    private static int[] majorMinor(String version) {
        if (version == null) return null;
        String s = SemverCompare.stripV(version.strip());
        int cut = indexOfAny(s, '-', '+');
        if (cut >= 0) s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }
}
