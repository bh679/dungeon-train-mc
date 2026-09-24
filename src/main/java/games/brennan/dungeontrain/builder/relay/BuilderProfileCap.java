package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;

import java.util.List;

/**
 * How many builds a player may keep in their relay profile, and how full theirs is.
 *
 * <p>The relay caps a profile at {@code carriage_profile_per_owner} builds and enforces it by
 * <b>deleting the author's oldest</b> to make room for a new one — silently, with no tombstone and
 * nothing said to the uploader. A player who kept building past the cap would lose their earliest
 * work to their latest, and the first they would know of it is a build missing from My Builds.</p>
 *
 * <p>So the mod refuses to be the thing that triggers that: an upload that would overflow the profile
 * is not sent, and the player is told to remove one instead. Choosing which build to lose is theirs
 * to make, and the relay's rule — oldest first — is the one choice nobody would pick deliberately.</p>
 *
 * <p><b>The cap comes from the relay.</b> {@code /carriages/mine} reports the owner's own cap beside
 * their builds ({@link SharedCarriageClient.Mine#cap()}) — the shared default for most players, a
 * per-player exception for a few (the dev client's {@code Dev} keeps 2000). Callers pass that number
 * in. {@link #DEFAULT_PROFILE_BUILDS} is only the stand-in for a relay too old to report one, and is
 * the relay's own default for that key.</p>
 */
public final class BuilderProfileCap {

    /**
     * The relay's {@code carriage_profile_per_owner} default, used when a reply carries no {@code cap}.
     * Keep in step with {@code cap-config.js}.
     */
    public static final int DEFAULT_PROFILE_BUILDS = 200;

    private BuilderProfileCap() {}

    /**
     * How many of a listing's builds count against the cap.
     *
     * <p>Only {@code visibility='profile'} rows do: the relay's own eviction filters on exactly that,
     * so a build the player has published to the train is not occupying a profile slot and must not
     * be counted as though it were.</p>
     */
    public static int used(List<SharedCarriageClient.ProfileBuild> builds) {
        if (builds == null) return 0;
        int used = 0;
        for (SharedCarriageClient.ProfileBuild build : builds) {
            if ("profile".equals(build.visibility())) used++;
        }
        return used;
    }

    /** How many more builds may be uploaded before the relay would start evicting. Never negative. */
    public static int remaining(int used, int cap) {
        return Math.max(0, cap - used);
    }

    /** Whether one more build would overflow the profile — i.e. cost the player their oldest. */
    public static boolean isFull(int used, int cap) {
        return remaining(used, cap) <= 0;
    }
}
