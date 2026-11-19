package games.brennan.dungeontrain.client.links;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Which outbound links the client will accept from the relay, and open.
 *
 * <p>Every link must be {@code https}, parse as a URI, carry no user-info and no port other than
 * 443 (plain http only from a local test relay, on loopback).</p>
 *
 * <p>A <b>payment</b> link must also be signed with Brennan's payment-link key: {@link OfficialLinks}
 * takes a payment key's relay value only from {@link PaymentLinkSignature}, so a tampered
 * {@code /links} response, a bad override on the relay's admin page or a compromised relay cannot
 * point a money button anywhere new. Any host is fine once signed, so a new provider needs no
 * release. This class only judges the URL's shape, which is also re-checked when the link opens.</p>
 *
 * <p>Pure: no Minecraft on any path.</p>
 */
public final class LinkPolicy {

    public static final String KEY_PATREON = "patreon";
    public static final String KEY_PAYMENT = "payment";
    public static final String KEY_PAYMENT_CN = "payment_cn";
    public static final String KEY_PAYMENT_CN_CHECKOUT = "payment_cn_checkout";
    public static final String KEY_PAYMENT_CHECKOUT = "payment_checkout";
    public static final String KEY_REWARDS_PAY = "rewards_pay";

    static final int MAX_URL = 500;

    /** Our own domains: the domain itself and any subdomain (we control their DNS). */
    static final Set<String> FIRST_PARTY_DOMAINS = Set.of("brennan.games", "dungeon.training");

    /** The keys whose relay value must carry a payment-link signature ({@link PaymentLinkSignature}). */
    static final Set<String> PAYMENT_KEYS = Set.of(
            KEY_PATREON, KEY_PAYMENT, KEY_PAYMENT_CN, KEY_PAYMENT_CN_CHECKOUT, KEY_PAYMENT_CHECKOUT, KEY_REWARDS_PAY);

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    /** Env var of the local/self-hosted relay override (see {@code DungeonTrain.relayBaseUrl}). */
    private static final String RELAY_OVERRIDE_ENV = "DUNGEONTRAIN_RELAY_BASE_URL";

    /** Null until first read; a test seam can pin it. */
    private static volatile Boolean localRelay;

    private LinkPolicy() {}

    /**
     * Whether {@code url} has an acceptable shape for relay key {@code key}: https and well-formed, or
     * http on loopback from a local test relay. Where a payment link came from (its signature) is
     * {@link OfficialLinks}' check, not this one.
     */
    public static boolean allowed(String key, String url) {
        URI uri = parse(url);
        if (uri == null) return false;
        if ("http".equals(scheme(uri))) {
            // Only the local test relay may serve plain http, and only on loopback.
            return isLocalRelayLoopback(url);
        }
        return true;
    }

    /**
     * True when a local test relay is in use and {@code url} points at loopback. Such a relay cannot
     * sign with the real key, so its payment links are taken unsigned; nothing else is.
     */
    static boolean isLocalRelayLoopback(String url) {
        URI uri = parse(url);
        return uri != null && localRelay() && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /** True when {@code key} is one of the payment keys, whose relay value must be signed. */
    public static boolean isPaymentKey(String key) {
        return key != null && PAYMENT_KEYS.contains(key);
    }

    /** The lower-cased host of a well-formed link, or null. For display on the confirm screen. */
    public static String hostOf(String url) {
        URI uri = parse(url);
        return uri == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code host} is {@code brennan.games}, {@code dungeon.training} or a subdomain. */
    public static boolean isFirstParty(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : FIRST_PARTY_DOMAINS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    /**
     * {@code url} as a URI when it is a well-formed http(s) link with an ASCII host, no user-info
     * and no port other than 443, else null. Scheme beyond http/https is rejected here; whether
     * http is acceptable is {@link #allowed}'s call.
     */
    static URI parse(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty() || s.length() > MAX_URL) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c > 0x7E) return null;
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
        if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isEmpty()) return null;
        // https: default port only. http (local relay, loopback only) may name its dev port.
        int port = uri.getPort();
        if ("https".equals(scheme) && port != -1 && port != 443) return null;
        return uri;
    }

    /** {@code uri}'s scheme, lower-cased. */
    private static String scheme(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static boolean localRelay() {
        Boolean v = localRelay;
        if (v == null) {
            String env = System.getenv(RELAY_OVERRIDE_ENV);
            v = env != null && !env.isBlank();
            localRelay = v;
        }
        return v;
    }

    /** Test seam: pin whether a local relay override is in effect (null re-reads the env). */
    static void setLocalRelayForTest(Boolean on) {
        localRelay = on;
    }
}
