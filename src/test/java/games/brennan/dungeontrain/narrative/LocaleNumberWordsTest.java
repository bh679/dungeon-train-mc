package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks down {@link LocaleNumberWords} cardinal spelling for the localized death-ledger figures.
 * Values are the standard cardinal forms per language (small numbers + the irregular boundaries that
 * matter: teens, tens joiners, hundred/thousand words, and the special unit forms in vi/th).
 */
final class LocaleNumberWordsTest {

    private static void check(String loc, long n, String expected) {
        assertEquals(expected, LocaleNumberWords.forLocale(loc, n), loc + " " + n);
    }

    @Test @DisplayName("Spanish")
    void spanish() {
        check("es_es", 0, "cero");
        check("es_es", 1, "uno");
        check("es_es", 15, "quince");
        check("es_es", 16, "dieciséis");
        check("es_es", 21, "veintiuno");
        check("es_es", 22, "veintidós");
        check("es_es", 30, "treinta");
        check("es_es", 31, "treinta y uno");
        check("es_es", 100, "cien");
        check("es_es", 101, "ciento uno");
        check("es_es", 200, "doscientos");
        check("es_es", 555, "quinientos cincuenta y cinco");
        check("es_es", 999, "novecientos noventa y nueve");
        check("es_es", 1000, "mil");
        check("es_es", 1001, "mil uno");
        check("es_es", 2000, "dos mil");
        check("es_es", 21000, "veintiún mil");
        check("es_es", 100000, "cien mil");
        check("es_mx", 42, "cuarenta y dos");   // es_mx uses the same speller
    }

    @Test @DisplayName("Portuguese")
    void portuguese() {
        check("pt_br", 0, "zero");
        check("pt_br", 1, "um");
        check("pt_br", 16, "dezesseis");
        check("pt_br", 21, "vinte e um");
        check("pt_br", 100, "cem");
        check("pt_br", 101, "cento e um");
        check("pt_br", 200, "duzentos");
        check("pt_br", 235, "duzentos e trinta e cinco");
        check("pt_br", 1000, "mil");
        check("pt_br", 1001, "mil e um");
        check("pt_br", 2500, "dois mil e quinhentos");
        check("pt_br", 2530, "dois mil quinhentos e trinta");
    }

    @Test @DisplayName("Indonesian")
    void indonesian() {
        check("id_id", 0, "nol");
        check("id_id", 8, "delapan");
        check("id_id", 10, "sepuluh");
        check("id_id", 11, "sebelas");
        check("id_id", 12, "dua belas");
        check("id_id", 20, "dua puluh");
        check("id_id", 21, "dua puluh satu");
        check("id_id", 100, "seratus");
        check("id_id", 200, "dua ratus");
        check("id_id", 250, "dua ratus lima puluh");
        check("id_id", 1000, "seribu");
        check("id_id", 2000, "dua ribu");
    }

    @Test @DisplayName("Malay")
    void malay() {
        check("ms_my", 0, "sifar");
        check("ms_my", 8, "lapan");
        check("ms_my", 11, "sebelas");
        check("ms_my", 20, "dua puluh");
        check("ms_my", 100, "seratus");
        check("ms_my", 1000, "seribu");
    }

    @Test @DisplayName("Filipino")
    void filipino() {
        check("fil_ph", 0, "sero");
        check("fil_ph", 1, "isa");
        check("fil_ph", 11, "labing-isa");
        check("fil_ph", 20, "dalawampu");
        check("fil_ph", 21, "dalawampu't isa");
        check("fil_ph", 100, "sandaan");
        check("fil_ph", 200, "dalawang daan");
    }

    @Test @DisplayName("Vietnamese")
    void vietnamese() {
        check("vi_vn", 0, "không");
        check("vi_vn", 11, "mười một");
        check("vi_vn", 15, "mười lăm");
        check("vi_vn", 21, "hai mươi mốt");
        check("vi_vn", 24, "hai mươi tư");
        check("vi_vn", 25, "hai mươi lăm");
        check("vi_vn", 100, "một trăm");
        check("vi_vn", 105, "một trăm lẻ năm");
        check("vi_vn", 123, "một trăm hai mươi ba");
        check("vi_vn", 1000, "một nghìn");
        check("vi_vn", 1005, "một nghìn không trăm lẻ năm");
    }

    @Test @DisplayName("Thai")
    void thai() {
        check("th_th", 0, "ศูนย์");
        check("th_th", 1, "หนึ่ง");
        check("th_th", 10, "สิบ");
        check("th_th", 11, "สิบเอ็ด");
        check("th_th", 20, "ยี่สิบ");
        check("th_th", 21, "ยี่สิบเอ็ด");
        check("th_th", 100, "หนึ่งร้อย");
        check("th_th", 101, "หนึ่งร้อยเอ็ด");
        check("th_th", 1000, "หนึ่งพัน");
        check("th_th", 1234, "หนึ่งพันสองร้อยสามสิบสี่");
    }

    @Test @DisplayName("German")
    void german() {
        check("de_de", 0, "null");
        check("de_de", 1, "eins");
        check("de_de", 16, "sechzehn");
        check("de_de", 17, "siebzehn");
        check("de_de", 21, "einundzwanzig");
        check("de_de", 45, "fünfundvierzig");
        check("de_de", 30, "dreißig");
        check("de_de", 100, "einhundert");
        check("de_de", 101, "einhunderteins");
        check("de_de", 234, "zweihundertvierunddreißig");
        check("de_de", 1000, "eintausend");
        check("de_de", 2001, "zweitausendeins");
        check("de_de", 21000, "einundzwanzigtausend");
        check("de_de", 999999, "neunhundertneunundneunzigtausendneunhundertneunundneunzig");
    }

    @Test @DisplayName("French")
    void french() {
        check("fr_fr", 0, "zéro");
        check("fr_fr", 17, "dix-sept");
        check("fr_fr", 21, "vingt et un");
        check("fr_fr", 22, "vingt-deux");
        check("fr_fr", 71, "soixante et onze");
        check("fr_fr", 72, "soixante-douze");
        check("fr_fr", 80, "quatre-vingts");
        check("fr_fr", 81, "quatre-vingt-un");
        check("fr_fr", 91, "quatre-vingt-onze");
        check("fr_fr", 99, "quatre-vingt-dix-neuf");
        check("fr_fr", 100, "cent");
        check("fr_fr", 200, "deux cents");
        check("fr_fr", 201, "deux cent un");
        check("fr_fr", 1000, "mille");
        check("fr_fr", 2000, "deux mille");
        check("fr_fr", 21000, "vingt et un mille");
        check("fr_fr", 80000, "quatre-vingt mille");
        check("fr_fr", 200000, "deux cent mille");
        check("fr_ca", 99, "quatre-vingt-dix-neuf");   // fr_ca uses the same speller
    }

    @Test @DisplayName("Italian")
    void italian() {
        check("it_it", 0, "zero");
        check("it_it", 3, "tre");
        check("it_it", 21, "ventuno");
        check("it_it", 23, "ventitré");
        check("it_it", 28, "ventotto");
        check("it_it", 33, "trentatré");
        check("it_it", 100, "cento");
        check("it_it", 101, "centuno");
        check("it_it", 108, "centotto");
        check("it_it", 180, "centottanta");
        check("it_it", 200, "duecento");
        check("it_it", 234, "duecentotrentaquattro");
        check("it_it", 1000, "mille");
        check("it_it", 2000, "duemila");
        check("it_it", 1234, "milleduecentotrentaquattro");
        check("it_it", 100000, "centomila");
    }

    @Test @DisplayName("Dutch")
    void dutch() {
        check("nl_nl", 0, "nul");
        check("nl_nl", 8, "acht");
        check("nl_nl", 21, "eenentwintig");
        check("nl_nl", 22, "tweeëntwintig");
        check("nl_nl", 23, "drieëntwintig");
        check("nl_nl", 24, "vierentwintig");
        check("nl_nl", 100, "honderd");
        check("nl_nl", 234, "tweehonderdvierendertig");
        check("nl_nl", 1000, "duizend");
        check("nl_nl", 2000, "tweeduizend");
        check("nl_be", 24, "vierentwintig");   // nl_be uses the same speller
    }

    @Test @DisplayName("Polish")
    void polish() {
        check("pl_pl", 0, "zero");
        check("pl_pl", 15, "piętnaście");
        check("pl_pl", 21, "dwadzieścia jeden");
        check("pl_pl", 45, "czterdzieści pięć");
        check("pl_pl", 200, "dwieście");
        check("pl_pl", 234, "dwieście trzydzieści cztery");
        check("pl_pl", 1000, "tysiąc");
        check("pl_pl", 2000, "dwa tysiące");
        check("pl_pl", 5000, "pięć tysięcy");
        check("pl_pl", 12000, "dwanaście tysięcy");
        check("pl_pl", 21000, "dwadzieścia jeden tysięcy");
        check("pl_pl", 22000, "dwadzieścia dwa tysiące");
        check("pl_pl", 100000, "sto tysięcy");
    }

    @Test @DisplayName("Russian")
    void russian() {
        check("ru_ru", 0, "ноль");
        check("ru_ru", 15, "пятнадцать");
        check("ru_ru", 21, "двадцать один");
        check("ru_ru", 200, "двести");
        check("ru_ru", 234, "двести тридцать четыре");
        check("ru_ru", 1000, "тысяча");
        check("ru_ru", 2000, "две тысячи");
        check("ru_ru", 5000, "пять тысяч");
        check("ru_ru", 21000, "двадцать одна тысяча");
        check("ru_ru", 22000, "двадцать две тысячи");
        check("ru_ru", 100000, "сто тысяч");
        check("ru_ru", 123456, "сто двадцать три тысячи четыреста пятьдесят шесть");
    }

    @Test @DisplayName("Japanese")
    void japanese() {
        check("ja_jp", 0, "零");
        check("ja_jp", 10, "十");
        check("ja_jp", 11, "十一");
        check("ja_jp", 21, "二十一");
        check("ja_jp", 100, "百");
        check("ja_jp", 234, "二百三十四");
        check("ja_jp", 1000, "千");
        check("ja_jp", 10000, "一万");
        check("ja_jp", 20000, "二万");
        check("ja_jp", 100000, "十万");
        check("ja_jp", 12345, "一万二千三百四十五");
        check("ja_jp", 999999, "九十九万九千九百九十九");
    }

    @Test @DisplayName("Korean")
    void korean() {
        check("ko_kr", 0, "영");
        check("ko_kr", 10, "십");
        check("ko_kr", 11, "십일");
        check("ko_kr", 21, "이십일");
        check("ko_kr", 100, "백");
        check("ko_kr", 234, "이백삼십사");
        check("ko_kr", 1000, "천");
        check("ko_kr", 10000, "만");
        check("ko_kr", 20000, "이만");
        check("ko_kr", 100000, "십만");
        check("ko_kr", 12345, "만이천삼백사십오");
        check("ko_kr", 999999, "구십구만구천구백구십구");
    }

    @Test @DisplayName("Chinese numerals: Simplified vs Traditional myriad marker")
    void chineseTraditional() {
        // zhWords lives in DeathLoreStore (same package); Traditional swaps 万→萬.
        assertEquals("一万", DeathLoreStore.zhWords(10000, false));
        assertEquals("一萬", DeathLoreStore.zhWords(10000, true));
        assertEquals("九万九千九百九十九", DeathLoreStore.zhWords(99999, false));
        assertEquals("九萬九千九百九十九", DeathLoreStore.zhWords(99999, true));
        assertEquals("三千二百一十", DeathLoreStore.zhWords(3210, true));   // sub-万 glyphs identical
    }

    @Test @DisplayName("out of range → digits; unknown locale → null")
    void bounds() {
        check("es_es", 1_000_000, "1000000");
        check("th_th", -5, "-5");
        check("de_de", 1_000_000, "1000000");
        check("ja_jp", 1_000_000, "1000000");
        assertNull(LocaleNumberWords.forLocale("xx_zz", 5));
        assertNull(LocaleNumberWords.forLocale(null, 5));
    }
}
