package games.brennan.dungeontrain.client.support.currency;

import java.util.Locale;
import java.util.Map;

/**
 * The currency a player sees before they ever pick one, from the game language, so most players
 * never open the list. Brennan's table, plus the locales whose own currency is offered; anything
 * else is US dollars. The website's support page ({@code currency.js}) mirrors this map.
 */
public final class CurrencyDefaults {

    private static final Map<String, DisplayCurrency> BY_LANGUAGE = Map.ofEntries(
            Map.entry("en_us", DisplayCurrency.USD),
            Map.entry("en_au", DisplayCurrency.AUD),
            Map.entry("en_gb", DisplayCurrency.GBP),
            Map.entry("en_ca", DisplayCurrency.CAD),
            Map.entry("fr_ca", DisplayCurrency.CAD),
            Map.entry("en_nz", DisplayCurrency.NZD),
            Map.entry("de_de", DisplayCurrency.EUR),
            Map.entry("fr_fr", DisplayCurrency.EUR),
            Map.entry("es_es", DisplayCurrency.EUR),
            Map.entry("it_it", DisplayCurrency.EUR),
            Map.entry("nl_nl", DisplayCurrency.EUR),
            Map.entry("pt_pt", DisplayCurrency.EUR),
            Map.entry("zh_cn", DisplayCurrency.CNY),
            Map.entry("pt_br", DisplayCurrency.BRL),
            Map.entry("pl_pl", DisplayCurrency.PLN),
            Map.entry("ja_jp", DisplayCurrency.JPY),
            Map.entry("ko_kr", DisplayCurrency.KRW),
            Map.entry("es_mx", DisplayCurrency.MXN),
            Map.entry("en_in", DisplayCurrency.INR),
            Map.entry("hi_in", DisplayCurrency.INR));

    private CurrencyDefaults() {}

    /** The default for a Minecraft language code ("de_de"); US dollars for anything unmapped. */
    public static DisplayCurrency forLanguage(String language) {
        if (language == null) return DisplayCurrency.USD;
        return BY_LANGUAGE.getOrDefault(language.trim().toLowerCase(Locale.ROOT), DisplayCurrency.USD);
    }
}
