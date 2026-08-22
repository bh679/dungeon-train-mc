package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.ClientLanguage;
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
 * pay with WeChat Pay or Alipay. {@link OfficialLinks#paymentCn()} carries a Stripe link, and
 * {@link #useChinaPayment()} decides who sees it.</p>
 *
 * <p>Which methods that link actually offers is a property of the relay-served URL, not of this
 * class — currently WeChat Pay only, because Stripe declined the Alipay activation ("Online
 * Gaming" is an unsupported category for it). Nothing here needs to change if that widens: the
 * button opens whatever the relay serves. Only the button's label does, since it names the
 * method.</p>
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
     *
     * <p>Prefers the relay's checkout route when one is served, because that is the only way the
     * player's name reaches the checkout as a <b>visible, prefilled</b> field: Stripe accepts no
     * custom-field prefill from a Payment Link URL, so the name can only be baked in when the
     * session itself is created, which the relay does. Absent that key the behaviour is exactly what
     * shipped before — the raw link with {@code client_reference_id}, which credits the donation but
     * shows the player nothing.</p>
     *
     * <p>Note the route is still <b>gated on {@code payment_cn}</b> via {@link #useChinaPayment()}.
     * A checkout URL served without a China link must not conjure the button into existence for
     * anyone, and the raw link remains the relay's own fallback when Stripe is unreachable.</p>
     */
    public static String chinaUrl() {
        if (!useChinaPayment()) return null;
        String checkout = OfficialLinks.paymentCnCheckout();
        String locale = stripeLocale(selectedLocale());
        if (checkout != null) return withDisplayName(checkout, playerName(), locale);
        return withLocale(withClientReference(OfficialLinks.paymentCn(), playerName()), locale);
    }

    /**
     * Whether the three named price points can be offered at all — i.e. whether the relay has served
     * a checkout route to build them from.
     *
     * <p>False is the offline / pre-fetch / not-configured case, and both donation screens then
     * render exactly the layout they shipped with before tiers existed. That degrade is the whole
     * reason there is no baked fallback: three buttons that lead nowhere would be worse than the two
     * open-ended options they replaced.</p>
     */
    public static boolean tiersAvailable() {
        return OfficialLinks.paymentCheckout() != null;
    }

    /**
     * The checkout URL for one price point, or null when {@link #tiersAvailable()} is false.
     *
     * <p>Same relay route as {@link #chinaUrl()}, with the tier's {@code qty} appended — the relay
     * multiplies its unit price by it when creating the Checkout Session, and clamps it on arrival,
     * so nothing here has to be trusted downstream.</p>
     */
    public static String tierUrl(SupportTier tier) {
        String base = OfficialLinks.paymentCheckout();
        if (base == null || tier == null) return null;
        return withQuantity(withDisplayName(base, playerName(), stripeLocale(selectedLocale())), tier.quantity());
    }

    /**
     * Whether to hide the open-ended options — Custom Amount (Revolut) and Recurring (Patreon) —
     * because neither provider is reachable for this player. Both are walled in mainland China, so
     * the row is dropped rather than shown broken; the price points remain, since they run through
     * Stripe, which works there.
     *
     * <p><b>This is deliberately narrower than {@link #useChinaPayment()}'s gate.</b> That one covers
     * the whole {@code zh} family, because it decides which payment route to <i>offer</i> and erring
     * wide only costs a Traditional-Chinese reader a button they could also have used. This one
     * decides what to <i>take away</i>, and Taiwan and Hong Kong players genuinely can use Patreon
     * and Revolut — so taking those from them to keep one tidy predicate would be a real loss for no
     * gain. The two differ on purpose; don't collapse them.</p>
     */
    public static boolean hidesOpenEndedOptions() {
        return isSimplifiedChineseLocale(selectedLocale());
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

    /**
     * Whether a raw client locale belongs to the Chinese language family. Delegates to
     * {@link ClientLanguage#isChinese(String)} so the wide gate has exactly one definition — the
     * title screen's Bilibili icon asks the same question of the same family.
     */
    static boolean isChineseLocale(String locale) {
        return ClientLanguage.isChinese(locale);
    }

    /**
     * Whether a raw client locale is <b>Simplified</b> Chinese — {@code zh_cn}, or {@code lzh}
     * (Classical Chinese, which Minecraft renders in Simplified characters and which
     * {@link #REGIONAL_LOCALES} already treats as {@code zh} for Stripe).
     *
     * <p>Not routed through {@link LanguageFamily} for the same reason {@link #stripeLocale} isn't:
     * that collapses {@code zh_tw} and {@code zh_hk} in with {@code zh_cn}, and here the region is
     * precisely the question — it is what separates the players who can reach Patreon and Revolut
     * from the ones who can't.</p>
     */
    static boolean isSimplifiedChineseLocale(String locale) {
        if (locale == null) return false;
        String clean = locale.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        return "zh_cn".equals(clean) || "lzh".equals(clean);
    }

    /**
     * Append a price point's {@code qty} — the number of units of the relay's checkout price, which
     * Stripe multiplies into the charged amount. Values below 1 are dropped rather than sent: the
     * relay would clamp them anyway, and an omitted parameter is the honest way to say "no choice
     * made" (the checkout then uses the link's own quantity).
     */
    static String withQuantity(String base, int quantity) {
        if (base == null || quantity < 1) return base;
        return withParam(base, "qty", Integer.toString(quantity));
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
     * Attach the player's name and language to the relay's checkout route.
     *
     * <p>Unlike {@link #withClientReference} the name is <b>not</b> stripped to Stripe's
     * {@code client_reference_id} charset: this parameter is read by the relay, not by Stripe, and
     * it becomes the value the player sees in the field. It is URL-encoded rather than sanitised so
     * a name survives intact, and {@code +} is rewritten to {@code %20} because a literal plus in a
     * query string decodes back to a space on some stacks — the same reason
     * {@link #withPlayerNote} does it.</p>
     *
     * <p>The relay caps and cleans the value on arrival, so nothing here has to trust it either.</p>
     */
    static String withDisplayName(String base, String playerName, String stripeLocale) {
        if (base == null) return null;
        String url = base;
        String name = playerName == null ? "" : playerName.trim();
        if (!name.isEmpty()) {
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            url = withParam(url, "displayname", encoded);
        }
        return withLocale(url, stripeLocale);
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
