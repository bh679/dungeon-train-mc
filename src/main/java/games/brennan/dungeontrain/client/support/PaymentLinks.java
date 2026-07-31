package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.narrative.LanguageFamily;
import net.minecraft.client.Minecraft;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which donation link a given client should be offered, and how to build it.
 *
 * <p><b>Why a China-specific route exists.</b> Both default funnels assume a Visa/Mastercard:
 * Patreon's domain is blocked in mainland China and Revolut has no presence there. Chinese players
 * pay with Alipay or WeChat Pay. {@link OfficialLinks#paymentCn()} carries a Stripe link offering
 * both, and {@link #useChinaPayment()} decides who sees it.</p>
 *
 * <p><b>The gate is the client's language, not its location</b> — Minecraft exposes no country. A
 * Chinese-language client anywhere is offered the China route, and conversely a mainland player
 * running an English client is not. Language is the best signal available and errs toward showing
 * a working option to the people most likely to need it.</p>
 *
 * <p>The gate is the whole {@code zh} family, so {@code zh_tw} and {@code lzh} are included
 * alongside {@code zh_cn} — see {@link LanguageFamily}. Taiwan and Hong Kong players can in fact
 * use Patreon; treating all Chinese locales alike is a deliberate simplification, revisit if the
 * {@code donate_cn} funnel shows it mattering.</p>
 *
 * <p>Every method degrades to the pre-existing behaviour when the relay has not served a
 * {@code payment_cn} value, so a jar built before the link existed behaves exactly as it did.</p>
 */
public final class PaymentLinks {

    /** Stripe's length cap for {@code client_reference_id}. */
    private static final int MAX_CLIENT_REF = 200;

    /**
     * Minecraft locales whose REGION changes the Stripe tag, so the bare language prefix would be
     * wrong. Everything else falls through to its prefix (e.g. {@code de_de} → {@code de}).
     */
    private static final Map<String, String> REGIONAL_LOCALES = Map.of(
            "zh_cn", "zh",      // Simplified
            "zh_tw", "zh-TW",   // Traditional
            "zh_hk", "zh-HK",
            "lzh",   "zh",      // Classical Chinese — Simplified is the closest Stripe offers
            "pt_br", "pt-BR",
            "pt_pt", "pt",
            "es_mx", "es-419",  // Latin American Spanish
            "en_gb", "en-GB",
            "fr_ca", "fr-CA"
    );

    /**
     * Stripe's {@code locale} enum, less {@code auto} and the regional tags handled above. A
     * language absent here means Stripe has no translation, so we omit the parameter rather than
     * send a tag it would reject.
     */
    private static final Set<String> STRIPE_LOCALES = Set.of(
            "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fil", "fr", "hr", "hu", "id",
            "it", "ja", "ko", "lt", "lv", "ms", "mt", "nb", "nl", "pl", "pt", "ro", "ru", "sk",
            "sl", "sv", "th", "tr", "vi", "zh"
    );

    private PaymentLinks() {}

    /** True when this client should be offered the China payment route instead of Patreon. */
    public static boolean useChinaPayment() {
        return useChinaPayment(selectedLocale(), OfficialLinks.paymentCn());
    }

    /**
     * The China payment URL with the player's name and language attached, or null when
     * {@link #useChinaPayment()} is false.
     */
    public static String chinaUrl() {
        if (!useChinaPayment()) return null;
        String url = withClientReference(OfficialLinks.paymentCn(), playerName());
        return withLocale(url, stripeLocale(selectedLocale()));
    }

    /**
     * The direct-donation URL. When the base (relay-served or baked Revolut link) carries a
     * {@code note=} field the player's name is URL-encoded onto it, matching the historical Revolut
     * behaviour; a relay-rotated provider without a note field is used verbatim so the suffix can't
     * corrupt an unknown URL shape.
     */
    public static String donateUrl() {
        return withPlayerNote(OfficialLinks.payment(), playerName());
    }

    // --- pure logic (unit-tested; no Minecraft on these paths) -------------------------------

    /**
     * Whether a client on {@code locale} with {@code cnUrl} configured takes the China route.
     * A null {@code cnUrl} means the relay has not served one, so nothing changes.
     */
    static boolean useChinaPayment(String locale, String cnUrl) {
        return cnUrl != null && isChineseLocale(locale);
    }

    /** Whether a raw client locale belongs to the Chinese language family. */
    static boolean isChineseLocale(String locale) {
        return locale != null && "zh".equals(LanguageFamily.of(locale));
    }

    /** Append the encoded player name to {@code base} when, and only when, it carries a note field. */
    static String withPlayerNote(String base, String playerName) {
        if (base == null || !base.contains("note=")) return base;
        String encoded = URLEncoder.encode(playerName, StandardCharsets.UTF_8).replace("+", "%20");
        return base + encoded;
    }

    /**
     * Attach the player's name to a Stripe payment link as {@code client_reference_id} — the
     * Revolut {@code note=} field's counterpart, and the only way to carry an identity through
     * Checkout (Stripe has no name-prefill parameter). It is <b>not</b> shown to the player; it
     * surfaces on the payment in the Stripe Dashboard and in the {@code checkout.session.completed}
     * webhook, which is what lets a donation be matched back to a player.
     *
     * <p>Stripe accepts {@code [A-Za-z0-9_-]} up to {@value #MAX_CLIENT_REF} characters and
     * <b>silently drops</b> anything else, so the name is sanitised rather than trusted — and when
     * nothing survives, the parameter is left off entirely instead of sending a value Stripe would
     * discard. Minecraft usernames pass through untouched.</p>
     */
    static String withClientReference(String base, String playerName) {
        String ref = sanitizeClientReference(playerName);
        return ref.isEmpty() ? base : withParam(base, "client_reference_id", ref);
    }

    /**
     * Render the checkout in the player's own language via Stripe's {@code locale} parameter.
     *
     * <p>A null {@code stripeLocale} leaves the parameter off, which is Stripe's {@code auto} —
     * it falls back to the browser's locale. That is the right degrade: a language we can't map
     * is better served by the browser's guess than by a tag Stripe doesn't recognise.</p>
     */
    static String withLocale(String base, String stripeLocale) {
        return stripeLocale == null ? base : withParam(base, "locale", stripeLocale);
    }

    /** Append {@code key=value}, extending an existing query string rather than starting a new one. */
    private static String withParam(String base, String key, String value) {
        if (base == null) return null;
        return base + (base.indexOf('?') >= 0 ? '&' : '?') + key + '=' + value;
    }

    /**
     * Map a Minecraft client language to Stripe's {@code locale} tag, or null when Stripe has no
     * matching language (caller then omits the parameter and Stripe falls back to the browser).
     *
     * <p><b>Do not route this through {@link LanguageFamily}.</b> That collapses {@code zh_tw} into
     * {@code zh}, which would hand Traditional-Chinese readers a Simplified checkout — the exact
     * distinction Stripe draws between {@code zh} and {@code zh-TW}. Region matters here in a way
     * it deliberately doesn't for the book-language filter.</p>
     */
    static String stripeLocale(String mcLocale) {
        if (mcLocale == null) return null;
        String clean = mcLocale.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (clean.isEmpty()) return null;
        String exact = REGIONAL_LOCALES.get(clean);
        if (exact != null) return exact;
        int us = clean.indexOf('_');
        String prefix = us > 0 ? clean.substring(0, us) : clean;
        return STRIPE_LOCALES.contains(prefix) ? prefix : null;
    }

    /** Strip {@code playerName} to the characters Stripe accepts, capped at {@value #MAX_CLIENT_REF}. */
    static String sanitizeClientReference(String playerName) {
        if (playerName == null) return "";
        StringBuilder out = new StringBuilder(Math.min(playerName.length(), MAX_CLIENT_REF));
        for (int i = 0; i < playerName.length() && out.length() < MAX_CLIENT_REF; i++) {
            char c = playerName.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    // --- Minecraft-facing accessors ----------------------------------------------------------

    /**
     * The client's selected language, or null when there is no client (dedicated server, tests).
     * Null reads as "not Chinese", so a headless caller never takes the China route.
     */
    private static String selectedLocale() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getLanguageManager() == null ? null : mc.getLanguageManager().getSelected();
    }

    private static String playerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getUser() != null ? mc.getUser().getName() : "Player";
    }
}
