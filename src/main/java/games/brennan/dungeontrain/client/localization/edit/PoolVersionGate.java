package games.brennan.dungeontrain.client.localization.edit;

/**
 * Which approved translations THIS build is allowed to apply.
 *
 * <p>A translator works against the English they can see. A fix written against 0.551.15's wording
 * says nothing about what the same key said in 0.540 — the translator never read that text — so an
 * approved translation applies to the build it was written in and every LATER one, and never to an
 * older client. The relay reports the authoring build per unit and stays out of the decision:
 * only the client knows what it is running.</p>
 *
 * <p><b>Fails open.</b> A missing version (an older relay, or a submission from a client too old to
 * send one) and a version that will not parse both apply. Silently withholding work a human
 * reviewed and released is the worse of the two failures, and the honest default when the data
 * needed to judge is absent.</p>
 *
 * <p>Deliberately not {@code client/version/SemverCompare}: that one compares MAJOR.MINOR only and
 * documents ignoring PATCH on purpose, because it drives update notifications and must not fire on
 * the ~22-tick patch cascade between releases. This is a different question and wants the whole
 * version.</p>
 */
public final class PoolVersionGate {

    private static final int SEGMENTS = 3;

    private PoolVersionGate() {}

    /**
     * Whether a translation written against {@code authoredIn} may be applied by {@code clientVersion}.
     *
     * @param authoredIn    the submitting client's mod version, or blank when unknown
     * @param clientVersion this build's mod version
     */
    public static boolean applies(String authoredIn, String clientVersion) {
        int[] authored = parse(authoredIn);
        int[] client = parse(clientVersion);
        if (authored == null || client == null) {
            return true; // fail open — see the class note
        }
        for (int i = 0; i < SEGMENTS; i++) {
            if (client[i] != authored[i]) {
                return client[i] > authored[i];
            }
        }
        return true; // the build it was written in counts as compatible
    }

    /** {@code major.minor.patch} as three ints, or null when it is not a version we can order. */
    private static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String core = version.trim();
        if (core.startsWith("v") || core.startsWith("V")) {
            core = core.substring(1);
        }
        int dash = core.indexOf('-'); // drop any pre-release suffix; it does not order the text
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.");
        int[] out = new int[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            if (i >= parts.length) {
                out[i] = 0; // "0.551" is "0.551.0"
                continue;
            }
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (out[i] < 0) {
                return null;
            }
        }
        return out;
    }
}
