package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.narrative.LanguageFamily;
import net.minecraft.client.Minecraft;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    private PaymentLinks() {}

    /** True when this client should be offered the China payment route instead of Patreon. */
    public static boolean useChinaPayment() {
        return useChinaPayment(selectedLocale(), OfficialLinks.paymentCn());
    }

    /** The China payment URL, or null when {@link #useChinaPayment()} is false. */
    public static String chinaUrl() {
        return useChinaPayment() ? OfficialLinks.paymentCn() : null;
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
