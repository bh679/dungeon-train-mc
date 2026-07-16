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

    @Test @DisplayName("out of range → digits; unknown locale → null")
    void bounds() {
        check("es_es", 1_000_000, "1000000");
        check("th_th", -5, "-5");
        assertNull(LocaleNumberWords.forLocale("fr_fr", 5));
        assertNull(LocaleNumberWords.forLocale(null, 5));
    }
}
