package games.brennan.dungeontrain.client.support.currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CurrencyDisplayTest {

    @Test
    @DisplayName("the game language picks the default currency; anything unmapped is US dollars")
    void languageDefaults() {
        assertEquals(DisplayCurrency.USD, CurrencyDefaults.forLanguage("en_us"));
        assertEquals(DisplayCurrency.AUD, CurrencyDefaults.forLanguage("en_au"));
        assertEquals(DisplayCurrency.GBP, CurrencyDefaults.forLanguage("en_gb"));
        for (String eur : new String[] {"de_de", "fr_fr", "es_es", "it_it", "nl_nl", "pt_pt"}) {
            assertEquals(DisplayCurrency.EUR, CurrencyDefaults.forLanguage(eur), eur);
        }
        assertEquals(DisplayCurrency.CNY, CurrencyDefaults.forLanguage("zh_cn"));
        assertEquals(DisplayCurrency.BRL, CurrencyDefaults.forLanguage("pt_br"));
        assertEquals(DisplayCurrency.PLN, CurrencyDefaults.forLanguage("pl_pl"));
        assertEquals(DisplayCurrency.JPY, CurrencyDefaults.forLanguage("JA_JP"));
        assertEquals(DisplayCurrency.USD, CurrencyDefaults.forLanguage("ru_ru"));
        assertEquals(DisplayCurrency.USD, CurrencyDefaults.forLanguage("zh_tw"));
        assertEquals(DisplayCurrency.USD, CurrencyDefaults.forLanguage(null));
    }

    @Test
    @DisplayName("converted amounts round to whole units, symbol before or after")
    void approxFormatting() {
        assertEquals("$20", CurrencyFormat.approx(30, DisplayCurrency.USD, 0.652));
        assertEquals("€18", CurrencyFormat.approx(30, DisplayCurrency.EUR, 0.594));
        assertEquals("77 zł", CurrencyFormat.approx(30, DisplayCurrency.PLN, 2.56));
        assertEquals("R$109", CurrencyFormat.approx(30, DisplayCurrency.BRL, 3.64));
        assertEquals("¥2,961", CurrencyFormat.approx(30, DisplayCurrency.JPY, 98.7));
        assertEquals("₩144,000", CurrencyFormat.approx(160, DisplayCurrency.KRW, 900));
    }

    @Test
    @DisplayName("ISO codes resolve in any case; unknown codes are not offered")
    void byCode() {
        assertEquals(DisplayCurrency.EUR, DisplayCurrency.byCode("eur"));
        assertNull(DisplayCurrency.byCode("RUB"));
        assertNull(DisplayCurrency.byCode(""));
        assertNull(DisplayCurrency.byCode(null));
    }

    @Test
    @DisplayName("fxRates parse keeps known, positive, numeric rates only")
    void parsesRates() {
        Map<DisplayCurrency, Double> r = CurrencyRates.parse(
                "{\"ok\":true,\"currency\":\"AUD\",\"fxRates\":{\"USD\":0.652,\"EUR\":0.594,"
                        + "\"CHF\":0.55,\"GBP\":0,\"JPY\":\"x\",\"AUD\":1}}");
        assertEquals(Map.of(DisplayCurrency.USD, 0.652, DisplayCurrency.EUR, 0.594), r);
        assertTrue(CurrencyRates.parse("{\"ok\":true}").isEmpty(), "an older relay sends no table");
        assertTrue(CurrencyRates.parse("not json").isEmpty());
        assertTrue(CurrencyRates.parse(null).isEmpty());
    }
}
