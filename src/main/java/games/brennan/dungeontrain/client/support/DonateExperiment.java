package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Which arm of a running UI experiment this player is in.
 *
 * <p><b>The relay names the arms; the client picks its own.</b> The relay publishes the experiment
 * — an id, a salt and a weighted list of arm ids — on the donation summary every client already
 * fetches (dp-relay {@code experiments.js}), and each jar hashes its own uuid against that salt to
 * choose. Nothing about the assignment travels: the summary request carries no identity, stays
 * edge-cacheable, and stays outside the network-consent gate, exactly as it did before experiments
 * existed. What the operator keeps is the part worth keeping — arms and weights can be retuned, or
 * the whole experiment ended, without shipping a jar.</p>
 *
 * <p><b>Stable for the life of the experiment.</b> The choice is a pure function of
 * {@code salt + experiment id + uuid}, so a player sees the same page on every death, every
 * session and every machine they log into. Rotating the salt re-buckets everyone, which is how a
 * re-run against a fresh population is started.</p>
 *
 * <p><b>Every failure lands on the control layout.</b> No experiment, no uuid, a malformed block,
 * weights that sum to zero, or an arm id this jar has no code to draw — all of them resolve to
 * {@link #none()}, which renders the page exactly as it renders today. That last case matters
 * most: a relay can add an arm for a newer jar without an older jar drawing something it does not
 * understand.</p>
 */
public final class DonateExperiment {

    /** The resolved assignment. {@code arm} is null when no experiment applies. */
    public record Assignment(String experimentId, String arm) {

        public boolean active() {
            return experimentId != null && arm != null;
        }
    }

    private static final Assignment NONE = new Assignment(null, null);

    private DonateExperiment() {}

    /** The no-experiment assignment: control layout, and no dimension on the telemetry. */
    public static Assignment none() {
        return NONE;
    }

    /**
     * Resolve this player's arm from the relay's experiment block.
     *
     * @param exp  the block as parsed from the summary, or null when the relay served none
     * @param uuid the player's own uuid; null (an offline or unusual launcher) means no experiment
     * @param known the arm ids this jar can actually draw — an arm outside this set is declined
     */
    public static Assignment resolve(DonationSummaryClient.Experiment exp, UUID uuid,
                                     List<String> known) {
        if (exp == null || uuid == null || known == null || known.isEmpty()) return NONE;
        if (exp.id() == null || exp.id().isBlank()) return NONE;
        if (exp.salt() == null || exp.salt().isBlank()) return NONE;

        List<DonationSummaryClient.Arm> arms = exp.arms();
        if (arms == null || arms.size() < 2) return NONE;

        // Arms this jar cannot draw are dropped BEFORE the weighting rather than being picked and
        // then declined. Declining after the fact would silently hand every player who drew an
        // unknown arm to control, quietly inflating control against a relay running a newer set.
        double total = 0.0;
        for (DonationSummaryClient.Arm a : arms) {
            if (a.weight() > 0 && known.contains(a.id())) total += a.weight();
        }
        if (total <= 0.0) return NONE;

        double point = fraction(exp.salt() + ":" + exp.id() + ":" + uuid) * total;
        double running = 0.0;
        for (DonationSummaryClient.Arm a : arms) {
            if (a.weight() <= 0 || !known.contains(a.id())) continue;
            running += a.weight();
            if (point < running) return new Assignment(exp.id(), a.id());
        }
        // Floating-point drift at the very top of the range: the last eligible arm is the answer.
        for (int i = arms.size() - 1; i >= 0; i--) {
            DonationSummaryClient.Arm a = arms.get(i);
            if (a.weight() > 0 && known.contains(a.id())) return new Assignment(exp.id(), a.id());
        }
        return NONE;
    }

    /**
     * A stable, uniformly-distributed {@code [0,1)} from a key, via the first six bytes of its
     * SHA-256.
     *
     * <p>SHA-256 rather than {@link String#hashCode()}: the point is an even split, and a hash
     * built for hash-table bucketing has no such guarantee across the short, highly-similar keys
     * a salt-plus-uuid produces. Six bytes is 48 bits of resolution, far past what a percentage
     * split needs, and stays well inside a double's exact-integer range.</p>
     *
     * <p>A JVM with no SHA-256 (there is no such supported JVM, but the checked exception is real)
     * yields {@code 0}, which is the first arm — a deterministic answer rather than a crash on the
     * death screen.</p>
     */
    static double fraction(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            long acc = 0L;
            for (int i = 0; i < 6; i++) {
                acc = (acc << 8) | (digest[i] & 0xFFL);
            }
            return (double) acc / (double) (1L << 48);
        } catch (NoSuchAlgorithmException e) {
            return 0.0;
        }
    }
}
