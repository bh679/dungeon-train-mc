package games.brennan.dungeontrain.client.links;

import java.util.HashMap;
import java.util.Map;

/**
 * Session-scoped holder for the official outbound links (Discord invite, Patreon, direct-payment,
 * hosting affiliate). Baked-in fallbacks ship in the jar so every screen works offline; when the
 * relay is reachable the values from {@code GET /<CAP>/links} overlay them, letting a rotated link
 * (new invite, changed payment provider, updated affiliate deal) reach already-shipped jars.
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

    /** Called by the fetcher with the raw relay map; invalid entries are dropped, valid ones kept. */
    static void accept(Map<String, String> raw) {
        relay = sanitize(raw);
    }

    /** Called by the fetcher when the request errored — the next ensureFetched() will retry. */
    static void markFailed() {
        failed = true;
    }

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
