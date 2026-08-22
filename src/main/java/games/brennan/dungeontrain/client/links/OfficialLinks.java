package games.brennan.dungeontrain.client.links;

import java.util.HashMap;
import java.util.Map;

/**
 * Session-scoped holder for the official outbound links (Discord invite, Bilibili channel, Patreon,
 * direct-payment, hosting affiliate). Baked-in fallbacks ship in the jar so every screen works
 * offline; when the relay is reachable the values from {@code GET /<CAP>/links} overlay them,
 * letting a rotated link (new invite, changed payment provider, updated affiliate deal) reach
 * already-shipped jars.
 *
 * <p>Mirrors {@link games.brennan.dungeontrain.client.version.VersionCheckState}: one JVM, one
 * successful fetch — repeat {@link #ensureFetched()} calls are idempotent unless the previous
 * attempt failed, in which case the next title-screen build retries. All writes happen on the
 * fetcher's HTTP completion thread; readers only touch {@code volatile} fields.</p>
 *
 * <p>Each relay value is validated independently ({@link #isValidUrl}) — a missing or malformed
 * key degrades to that key's baked fallback rather than breaking the link.</p>
 */
public final class OfficialLinks {

    // Baked fallbacks — keep in lock-step with the relay's links.js DEFAULTS.
    static final String FALLBACK_DISCORD =
            "https://discord.gg/jdKAwb6rbW";
    static final String FALLBACK_PATREON =
            "https://www.patreon.com/brennanhatton";
    /** Revolut direct-donation base ($25 AUD default); callers may append an encoded note suffix. */
    static final String FALLBACK_PAYMENT =
            "https://revolut.me/brennacg7?currency=AUD&amount=2500&note=Dungeon%20Train%20";
    static final String FALLBACK_AFFILIATE =
            "https://billing.kinetichosting.com/aff.php?aff=1461";
    /**
     * The Bilibili channel — the community link offered to Chinese-language clients, for whom the
     * Discord invite is a dead end (Discord is blocked in mainland China). Baked like the others
     * rather than relay-only: the players who need it are exactly the ones least likely to have a
     * clean route to the relay, so it must work with no fetch at all.
     */
    static final String FALLBACK_BILIBILI =
            "https://space.bilibili.com/3707029436762273";
    /**
     * The China-facing payment link (a Stripe link; WeChat Pay today) has NO baked fallback on
     * purpose — it is relay-only. Patreon and Revolut are both walled in mainland China, so this is
     * the one route that works there; but until the relay serves {@code payment_cn}, {@link
     * #paymentCn()} returns null and every screen keeps its pre-existing behaviour. That lets the
     * link go live for already-shipped jars the moment {@code PAYMENT_CN_URL} is set on the relay,
     * with no rebuild and no re-release.
     */

    private static final int MAX_URL = 500;

    /** Sanitized relay overlay — only ever swapped whole, never mutated. */
    private static volatile Map<String, String> relay = Map.of();
    private static volatile boolean attempted;
    private static volatile boolean failed;

    private OfficialLinks() {}

    /** Kick off the one-per-session relay fetch (retrying a previously failed attempt). */
    public static void ensureFetched() {
        if (!attempted || failed) {
            attempted = true;
            failed = false;
            OfficialLinksFetcher.fetchAsync();
        }
    }

    public static String discord()   { return resolve("discord", FALLBACK_DISCORD); }
    public static String patreon()   { return resolve("patreon", FALLBACK_PATREON); }
    public static String payment()   { return resolve("payment", FALLBACK_PAYMENT); }
    public static String affiliate() { return resolve("affiliate", FALLBACK_AFFILIATE); }
    /** The Bilibili channel — shown above Discord on Chinese-language clients, and only there. */
    public static String bilibili()  { return resolve("bilibili", FALLBACK_BILIBILI); }

    /** The China payment link, or {@code null} when the relay has not served a valid one. */
    public static String paymentCn() { return resolve("payment_cn", null); }

    /**
     * The relay's own checkout route, which builds a Stripe Checkout Session carrying the player's
     * name already filled into the display-name field — something a raw Stripe link cannot do, since
     * a Payment Link accepts no custom-field prefill from its URL.
     *
     * <p>Relay-only and separate from {@link #paymentCn()} on purpose. Jars shipped before this
     * existed read only {@code payment_cn} and are untouched by it; unsetting the key on the relay
     * drops even new jars back to the raw link with no rebuild. The China route stays gated on
     * {@code payment_cn}, so this key never changes WHO is offered the button — only where it
     * points.</p>
     */
    public static String paymentCnCheckout() { return resolve("payment_cn_checkout", null); }

    /**
     * The checkout route served to <b>every</b> client, backing the three named price points (see
     * {@link games.brennan.dungeontrain.client.support.SupportTier}). In practice it points at the
     * same relay route as {@link #paymentCnCheckout()} — that route already offers card alongside
     * WeChat Pay, so it is China-<i>named</i>, not China-limited.
     *
     * <p>It is nevertheless a separate key, because {@code payment_cn_checkout} doubles as the China
     * route's kill switch: unsetting it to pull the China button must not also strip the price
     * points from everyone else. The fallback to it exists only so a relay that predates
     * {@code payment_checkout} still lights the tiers up.</p>
     *
     * <p>Relay-only, so a client that cannot reach the relay gets null and both screens fall back to
     * the open-ended options they have always shown. There is deliberately no baked default: a
     * Stripe URL guessed offline would be three dead buttons in front of someone trying to pay.</p>
     */
    public static String paymentCheckout() {
        String general = resolve("payment_checkout", null);
        return general != null ? general : paymentCnCheckout();
    }

    /** Called by the fetcher with the raw relay map; invalid entries are dropped, valid ones kept. */
    static void accept(Map<String, String> raw) {
        relay = sanitize(raw);
    }

    /** Called by the fetcher when the request errored — the next ensureFetched() will retry. */
    static void markFailed() {
        failed = true;
    }

    /** The relay's value for {@code key}, else {@code fallback} — which may be null for relay-only keys. */
    private static String resolve(String key, String fallback) {
        String v = relay.get(key);
        return v != null ? v : fallback;
    }

    /** New map holding only the entries whose values pass {@link #isValidUrl}, trimmed. */
    static Map<String, String> sanitize(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (isValidUrl(e.getValue())) out.put(e.getKey(), e.getValue().trim());
        }
        return Map.copyOf(out);
    }

    /**
     * True when {@code v} is a plausible outbound link: non-empty after trim, http(s) scheme, sane
     * length, no embedded whitespace. Anything else keeps that key's baked fallback — the relay is
     * trusted, but a typo'd server-side value must never yield a dead or dangerous URI.
     */
    static boolean isValidUrl(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.isEmpty() || s.length() > MAX_URL) return false;
        if (!(s.startsWith("https://") || s.startsWith("http://"))) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    /** Test seam — reset to the pristine no-overlay state. */
    static void reset() {
        relay = Map.of();
        attempted = false;
        failed = false;
    }
}
