package games.brennan.dungeontrain.narrative;

/**
 * Cardinal number → words for the locales Dungeon Train ships death-ledger prose in, so figures
 * spell out in the prose language (e.g. Spanish "cero", Thai "ศูนย์") rather than English words or
 * bare digits. English and Simplified Chinese are handled by {@code DeathLoreStore} itself
 * (its {@code words()} / {@code zhWords()}); this covers the rest.
 *
 * <p>Each speller covers {@code 0..999_999} — comfortably beyond any single-run death stat — and
 * the caller ({@code DeathLoreStore.words}) falls back to Arabic digits outside that range, matching
 * the bounded approach {@code zhWords} already uses.</p>
 *
 * <p><b>Review note:</b> Vietnamese, Filipino (Tagalog) and Thai cardinal grammar (ligatures,
 * special unit forms like Vietnamese {@code mốt}/{@code lăm}, Thai {@code เอ็ด}/{@code ยี่}) is
 * intricate; these three especially warrant a native-speaker pass alongside the prose review.</p>
 */
public final class LocaleNumberWords {

    private static final long MAX = 999_999L;

    private LocaleNumberWords() {}

    /** Spell {@code n} in {@code localeCode}'s language, or return null if this class doesn't handle it. */
    public static String forLocale(String localeCode, long n) {
        if (localeCode == null) return null;
        String base = localeCode.length() >= 2 ? localeCode.substring(0, 2) : localeCode;
        if (n < 0 || n > MAX) {
            // Out of spelled range — let the caller use digits (all these languages use Arabic numerals).
            return switch (base) {
                case "es", "pt", "id", "ms", "fi", "vi", "th" -> Long.toString(n);
                default -> null;
            };
        }
        return switch (base) {
            case "es" -> es(n);
            case "pt" -> pt(n);
            case "id" -> malayic(n, false);
            case "ms" -> malayic(n, true);
            case "fi" -> fil(n);   // fil_ph
            case "vi" -> vi(n);
            case "th" -> th(n);
            default -> null;
        };
    }

    // ---------------------------------------------------------------- Spanish

    private static final String[] ES_0_29 = {
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete",
        "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés",
        "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"
    };
    private static final String[] ES_TENS = {
        "", "", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"
    };
    private static final String[] ES_HUNDREDS = {
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos"
    };

    private static String esBelow100(long n) {
        if (n < 30) return ES_0_29[(int) n];
        long t = n / 10, r = n % 10;
        return r == 0 ? ES_TENS[(int) t] : ES_TENS[(int) t] + " y " + ES_0_29[(int) r];
    }

    private static String esBelow1000(long n) {
        if (n < 100) return esBelow100(n);
        long h = n / 100, r = n % 100;
        if (h == 1 && r == 0) return "cien";
        return ES_HUNDREDS[(int) h] + (r == 0 ? "" : " " + esBelow100(r));
    }

    private static String es(long n) {
        if (n < 1000) return esBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String thousands = (k == 1) ? "mil" : esApocope(esBelow1000(k)) + " mil";
        return r == 0 ? thousands : thousands + " " + esBelow1000(r);
    }

    /** Apocopate a thousands-group ending in "uno" before "mil": uno→un, veintiuno→veintiún. */
    private static String esApocope(String s) {
        if (s.endsWith("veintiuno")) return s.substring(0, s.length() - 9) + "veintiún";
        if (s.endsWith("uno")) return s.substring(0, s.length() - 3) + "un";
        return s;
    }

    // ---------------------------------------------------------------- Portuguese

    private static final String[] PT_0_19 = {
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
        "dez", "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete",
        "dezoito", "dezenove"
    };
    private static final String[] PT_TENS = {
        "", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa"
    };
    private static final String[] PT_HUNDREDS = {
        "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos",
        "seiscentos", "setecentos", "oitocentos", "novecentos"
    };

    private static String ptBelow100(long n) {
        if (n < 20) return PT_0_19[(int) n];
        long t = n / 10, r = n % 10;
        return r == 0 ? PT_TENS[(int) t] : PT_TENS[(int) t] + " e " + PT_0_19[(int) r];
    }

    private static String ptBelow1000(long n) {
        if (n < 100) return ptBelow100(n);
        long h = n / 100, r = n % 100;
        if (h == 1 && r == 0) return "cem";
        return PT_HUNDREDS[(int) h] + (r == 0 ? "" : " e " + ptBelow100(r));
    }

    private static String pt(long n) {
        if (n < 1000) return ptBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String thousands = (k == 1) ? "mil" : ptBelow1000(k) + " mil";
        if (r == 0) return thousands;
        // Portuguese joins the final group with "e" when it is < 100 or a whole number of hundreds.
        String join = (r < 100 || r % 100 == 0) ? " e " : " ";
        return thousands + join + ptBelow1000(r);
    }

    // ---------------------------------------------------------------- Indonesian / Malay (malayic)

    private static String malayic(long n, boolean malay) {
        String[] u = malay
            ? new String[]{"sifar", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "lapan", "sembilan"}
            : new String[]{"nol", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan"};
        return malayic(n, u);
    }

    private static String malayic(long n, String[] u) {
        if (n < 10) return u[(int) n];
        if (n < 20) {
            if (n == 10) return "sepuluh";
            if (n == 11) return "sebelas";
            return u[(int) (n - 10)] + " belas";
        }
        if (n < 100) {
            long t = n / 10, r = n % 10;
            return u[(int) t] + " puluh" + (r == 0 ? "" : " " + u[(int) r]);
        }
        if (n < 1000) {
            long h = n / 100, r = n % 100;
            String hw = (h == 1) ? "seratus" : u[(int) h] + " ratus";
            return hw + (r == 0 ? "" : " " + malayic(r, u));
        }
        long k = n / 1000, r = n % 1000;
        String kw = (k == 1) ? "seribu" : malayic(k, u) + " ribu";
        return kw + (r == 0 ? "" : " " + malayic(r, u));
    }

    // ---------------------------------------------------------------- Filipino (Tagalog)

    private static final String[] FIL_0_19 = {
        "sero", "isa", "dalawa", "tatlo", "apat", "lima", "anim", "pito", "walo", "siyam",
        "sampu", "labing-isa", "labindalawa", "labintatlo", "labing-apat", "labinlima",
        "labing-anim", "labimpito", "labingwalo", "labinsiyam"
    };
    private static final String[] FIL_TENS = {
        "", "", "dalawampu", "tatlumpu", "apatnapu", "limampu", "animnapu", "pitumpu", "walumpu", "siyamnapu"
    };
    private static final String[] FIL_HUNDREDS = {
        "", "sandaan", "dalawang daan", "tatlong daan", "apat na raan", "limang daan",
        "anim na raan", "pitong daan", "walong daan", "siyam na raan"
    };

    private static String filBelow100(long n) {
        if (n < 20) return FIL_0_19[(int) n];
        long t = n / 10, r = n % 10;
        return r == 0 ? FIL_TENS[(int) t] : FIL_TENS[(int) t] + "'t " + FIL_0_19[(int) r];
    }

    private static String filBelow1000(long n) {
        if (n < 100) return filBelow100(n);
        long h = n / 100, r = n % 100;
        return FIL_HUNDREDS[(int) h] + (r == 0 ? "" : " at " + filBelow100(r));
    }

    private static String fil(long n) {
        if (n < 1000) return filBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String kw = (k == 1) ? "sanlibo" : filBelow1000(k) + " libo";
        return kw + (r == 0 ? "" : " at " + filBelow1000(r));
    }

    // ---------------------------------------------------------------- Vietnamese

    private static final String[] VI_U = {
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    };

    /** Two-digit group 0..99. {@code lead} = there is a higher non-zero place before this group. */
    private static String viBelow100(long n, boolean lead) {
        if (n < 10) {
            // A bare unit after a higher place reads "lẻ <unit>" (handled by callers); here just the unit.
            return VI_U[(int) n];
        }
        long t = n / 10, r = n % 10;
        String tens = (t == 1) ? "mười" : VI_U[(int) t] + " mươi";
        if (r == 0) return tens;
        String unit = switch ((int) r) {
            case 1 -> (t == 1) ? "một" : "mốt";   // 11 = mười một, 21 = hai mươi mốt
            case 4 -> (t == 1) ? "bốn" : "tư";     // 14 = mười bốn, 24 = hai mươi tư
            case 5 -> "lăm";                        // 15 = mười lăm, 25 = hai mươi lăm
            default -> VI_U[(int) r];
        };
        return tens + " " + unit;
    }

    private static String viBelow1000(long n, boolean lead) {
        if (n < 100) return viBelow100(n, lead);
        long h = n / 100, r = n % 100;
        String head = VI_U[(int) h] + " trăm";
        if (r == 0) return head;
        if (r < 10) return head + " lẻ " + VI_U[(int) r];   // 105 = một trăm lẻ năm
        return head + " " + viBelow100(r, true);
    }

    private static String vi(long n) {
        if (n < 1000) return viBelow1000(n, false);
        long k = n / 1000, r = n % 1000;
        String head = viBelow1000(k, false) + " nghìn";
        if (r == 0) return head;
        if (r < 100) {
            // zero hundreds in the low group: "không trăm lẻ <unit>" / "không trăm <tens>"
            String tail = (r < 10) ? "không trăm lẻ " + VI_U[(int) r] : "không trăm " + viBelow100(r, true);
            return head + " " + tail;
        }
        return head + " " + viBelow1000(r, true);
    }

    // ---------------------------------------------------------------- Thai

    private static final String[] TH_U = {
        "ศูนย์", "หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า"
    };
    private static final String[] TH_PLACE = {"", "สิบ", "ร้อย", "พัน", "หมื่น", "แสน"};

    private static String th(long n) {
        if (n == 0) return TH_U[0];
        // Build from most-significant digit down, place by place (units..แสน).
        int[] digits = new int[6];
        long x = n;
        int len = 0;
        while (x > 0) { digits[len++] = (int) (x % 10); x /= 10; }
        StringBuilder sb = new StringBuilder();
        for (int pos = len - 1; pos >= 0; pos--) {
            int d = digits[pos];
            if (d == 0) continue;
            if (pos == 1) {
                // tens place: 1 -> "สิบ", 2 -> "ยี่สิบ", else digit + "สิบ"
                if (d == 1) sb.append("สิบ");
                else if (d == 2) sb.append("ยี่สิบ");
                else sb.append(TH_U[d]).append("สิบ");
            } else if (pos == 0) {
                // units place: 1 -> "เอ็ด" when a higher place exists, else "หนึ่ง"
                sb.append(d == 1 && len > 1 ? "เอ็ด" : TH_U[d]);
            } else {
                sb.append(TH_U[d]).append(TH_PLACE[pos]);
            }
        }
        return sb.toString();
    }
}
