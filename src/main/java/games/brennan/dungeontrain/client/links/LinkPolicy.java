package games.brennan.dungeontrain.client.links;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which outbound links the client will accept from the relay, and open.
 *
 * <p>Every link must be {@code https}, parse as a URI, carry no user-info and no port other than
 * 443. On top of that, a <b>payment</b> key accepts only its provider's own hosts plus the
 * first-party domains ({@code brennan.games}, {@code dungeon.training} and their subdomains). A
 * tampered {@code /links} response or a bad override saved on the relay's admin page therefore
 * cannot point a money button at someone else's page: the value is dropped and that key degrades
 * as it does offline (see {@link OfficialLinks}).</p>
 *
 * <p><b>A new payment provider needs no release.</b> The relay serves a link on a host outside
 * this list through its own {@code /go/<key>} redirect on {@code brennan.games}, which passes the
 * client's query string on. Keep {@link #PAYMENT_HOSTS} in lock-step with the relay's copy in
 * {@code links.js}.</p>
 *
 * <p>Non-payment keys (Discord, channels, affiliate, mod whitelist) keep a looser rule: https and
 * the parse checks, any host, because those rotate to arbitrary sites and carry no money.</p>
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

    private static final Set<String> STRIPE = Set.of("buy.stripe.com", "checkout.stripe.com");

    /** Payment key → its provider's hosts, matched exactly (no subdomains). */
    static final Map<String, Set<String>> PAYMENT_HOSTS = Map.of(
            KEY_PATREON, Set.of("www.patreon.com", "patreon.com"),
            KEY_PAYMENT, Set.of("revolut.me"),
            KEY_PAYMENT_CN, STRIPE,
            KEY_PAYMENT_CN_CHECKOUT, STRIPE,
            KEY_PAYMENT_CHECKOUT, STRIPE,
            KEY_REWARDS_PAY, STRIPE
    );

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    /** Env var of the local/self-hosted relay override (see {@code DungeonTrain.relayBaseUrl}). */
    private static final String RELAY_OVERRIDE_ENV = "DUNGEONTRAIN_RELAY_BASE_URL";

    /** Null until first read; a test seam can pin it. */
    private static volatile Boolean localRelay;

    private LinkPolicy() {}

    /** Whether {@code url} may be used for relay key {@code key}. */
    public static boolean allowed(String key, String url) {
        URI uri = parse(url);
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme(uri))) {
            // Only the local test relay may serve plain http, and only on loopback.
            return localRelay() && LOOPBACK.contains(host);
        }
        if (!isPaymentKey(key)) return true;
        if (isFirstParty(host)) return true;
        if (localRelay() && LOOPBACK.contains(host)) return true;
        return PAYMENT_HOSTS.get(key).contains(host);
    }

    /** True when {@code key} is one of the payment keys with a host allowlist. */
    public static boolean isPaymentKey(String key) {
        return key != null && PAYMENT_HOSTS.containsKey(key);
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
