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
 * intricate; these especially warrant a native-speaker pass alongside the prose review. The same
 * applies to the batch-1 additions where cardinals inflect: Polish plural/case of {@code tysiąc}
 * ({@code tysiące}/{@code tysięcy}), Russian gender ({@code одна}/{@code две тысячи}), Italian vowel
 * elision ({@code ventuno}, {@code centottanta}), and Dutch diaeresis joins ({@code tweeëntwintig}).
 * German/French are handled by their standard written rules. Japanese ({@code 万}) and Korean
 * ({@code 만}) spell with CJK myriad grouping.</p>
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
                case "es", "pt", "id", "ms", "fi", "vi", "th",
                     "de", "fr", "it", "nl", "pl", "ru", "ja", "ko" -> Long.toString(n);
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
            case "de" -> de(n);
            case "fr" -> fr(n);
            case "it" -> it(n);
            case "nl" -> nl(n);
            case "pl" -> pl(n);
            case "ru" -> ru(n);
            case "ja" -> ja(n);
            case "ko" -> ko(n);
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

    // ---------------------------------------------------------------- German
    // German writes cardinals below a million as a single word, units before tens joined by "und".

    private static final String[] DE_0_19 = {
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
        "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn",
        "achtzehn", "neunzehn"
    };
    private static final String[] DE_TENS = {
        "", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig"
    };

    private static String deBelow100(long n) {
        if (n < 20) return DE_0_19[(int) n];
        long t = n / 10, r = n % 10;
        if (r == 0) return DE_TENS[(int) t];
        // "eins" apocopates to "ein" in compounds: einundzwanzig, not einsundzwanzig.
        String unit = (r == 1) ? "ein" : DE_0_19[(int) r];
        return unit + "und" + DE_TENS[(int) t];
    }

    private static String deBelow1000(long n) {
        if (n < 100) return deBelow100(n);
        long h = n / 100, r = n % 100;
        String head = (h == 1 ? "ein" : DE_0_19[(int) h]) + "hundert";
        return r == 0 ? head : head + deBelow100(r);
    }

    private static String de(long n) {
        if (n < 1000) return deBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String head = (k == 1 ? "ein" : deBelow1000(k)) + "tausend";
        return r == 0 ? head : head + deBelow1000(r);
    }

    // ---------------------------------------------------------------- French

    private static final String[] FR_0_16 = {
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
        "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"
    };
    private static final String[] FR_TENS = {
        "", "", "vingt", "trente", "quarante", "cinquante", "soixante"
    };

    private static String frBelow20(long n) {
        return (n < 17) ? FR_0_16[(int) n] : "dix-" + FR_0_16[(int) (n - 10)];
    }

    private static String frBelow100(long n) {
        if (n < 20) return frBelow20(n);
        if (n < 70) {
            long t = n / 10, r = n % 10;
            String tens = FR_TENS[(int) t];
            if (r == 0) return tens;
            if (r == 1) return tens + " et un";
            return tens + "-" + FR_0_16[(int) r];
        }
        if (n < 80) {                                   // 70..79 build on "soixante"
            if (n == 71) return "soixante et onze";
            return "soixante-" + frBelow20(n - 60);
        }
        if (n == 80) return "quatre-vingts";            // plural s only when nothing follows
        return "quatre-vingt-" + frBelow20(n - 80);     // 81..99, no "et"
    }

    /** Drop the plural "s" of cents/vingts when the group multiplies cent or mille. */
    private static String frDeplural(String s) {
        if (s.endsWith("cents")) return s.substring(0, s.length() - 1);
        if (s.endsWith("vingts")) return s.substring(0, s.length() - 1);
        return s;
    }

    private static String frBelow1000(long n) {
        if (n < 100) return frBelow100(n);
        long h = n / 100, r = n % 100;
        String head = (h == 1) ? "cent" : FR_0_16[(int) h] + " cent";
        if (r == 0) return (h > 1) ? head + "s" : head;   // deux cents, but cent
        return head + " " + frBelow100(r);                 // no plural s when followed
    }

    private static String fr(long n) {
        if (n < 1000) return frBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String head = (k == 1) ? "mille" : frDeplural(frBelow1000(k)) + " mille";
        return r == 0 ? head : head + " " + frBelow1000(r);
    }

    // ---------------------------------------------------------------- Italian
    // Italian writes cardinals below a million as one word, with tens/hundreds vowel-elision.

    private static final String[] IT_0_19 = {
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
        "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici",
        "diciassette", "diciotto", "diciannove"
    };
    private static final String[] IT_TENS = {
        "", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta"
    };

    private static boolean startsWithVowel(String s) {
        return !s.isEmpty() && "aeiouAEIOU".indexOf(s.charAt(0)) >= 0;
    }

    private static String itBelow100(long n) {
        if (n < 20) return IT_0_19[(int) n];
        long t = n / 10, r = n % 10;
        String tens = IT_TENS[(int) t];
        if (r == 0) return tens;
        if (r == 3) return tens + "tré";                          // ventitré, trentatré (accented)
        if (r == 1 || r == 8) return tens.substring(0, tens.length() - 1) + IT_0_19[(int) r]; // ventuno, ventotto
        return tens + IT_0_19[(int) r];
    }

    private static String itBelow1000(long n) {
        if (n < 100) return itBelow100(n);
        long h = n / 100, r = n % 100;
        String head = (h == 1) ? "cento" : IT_0_19[(int) h] + "cento";
        if (r == 0) return head;
        String tail = itBelow100(r);
        // cento elides its final "o" before a vowel-initial remainder: centottanta, centuno.
        if (startsWithVowel(tail)) head = head.substring(0, head.length() - 1);
        return head + tail;
    }

    private static String it(long n) {
        if (n < 1000) return itBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String head = (k == 1) ? "mille" : itBelow1000(k) + "mila";
        return r == 0 ? head : head + itBelow1000(r);
    }

    // ---------------------------------------------------------------- Dutch
    // Dutch writes cardinals below a million as one word, units before tens joined by "en".

    private static final String[] NL_0_19 = {
        "nul", "een", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen",
        "tien", "elf", "twaalf", "dertien", "veertien", "vijftien", "zestien", "zeventien",
        "achttien", "negentien"
    };
    private static final String[] NL_TENS = {
        "", "", "twintig", "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig", "negentig"
    };

    private static String nlBelow100(long n) {
        if (n < 20) return NL_0_19[(int) n];
        long t = n / 10, r = n % 10;
        if (r == 0) return NL_TENS[(int) t];
        String unit = NL_0_19[(int) r];
        // A unit ending in "e" (twee, drie) takes a diaeresis on the joining "en": tweeëntwintig.
        String joiner = unit.endsWith("e") ? "ën" : "en";
        return unit + joiner + NL_TENS[(int) t];
    }

    private static String nlBelow1000(long n) {
        if (n < 100) return nlBelow100(n);
        long h = n / 100, r = n % 100;
        String head = (h == 1) ? "honderd" : NL_0_19[(int) h] + "honderd";
        return r == 0 ? head : head + nlBelow100(r);
    }

    private static String nl(long n) {
        if (n < 1000) return nlBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String head = (k == 1) ? "duizend" : nlBelow1000(k) + "duizend";
        return r == 0 ? head : head + nlBelow1000(r);
    }

    // ---------------------------------------------------------------- Polish
    // Space-separated groups; "tysiąc" takes Polish plural forms by the thousands count.

    private static final String[] PL_0_19 = {
        "zero", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem", "dziewięć",
        "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście", "piętnaście",
        "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście"
    };
    private static final String[] PL_TENS = {
        "", "", "dwadzieścia", "trzydzieści", "czterdzieści", "pięćdziesiąt", "sześćdziesiąt",
        "siedemdziesiąt", "osiemdziesiąt", "dziewięćdziesiąt"
    };
    private static final String[] PL_HUNDREDS = {
        "", "sto", "dwieście", "trzysta", "czterysta", "pięćset", "sześćset", "siedemset",
        "osiemset", "dziewięćset"
    };

    private static String plBelow100(long n) {
        if (n < 20) return PL_0_19[(int) n];
        long t = n / 10, r = n % 10;
        return r == 0 ? PL_TENS[(int) t] : PL_TENS[(int) t] + " " + PL_0_19[(int) r];
    }

    private static String plBelow1000(long n) {
        if (n < 100) return plBelow100(n);
        long h = n / 100, r = n % 100;
        return PL_HUNDREDS[(int) h] + (r == 0 ? "" : " " + plBelow100(r));
    }

    /** Polish plural of "tysiąc" governed by the thousands count k. */
    private static String plThousandWord(long k) {
        long lastDigit = k % 10, lastTwo = k % 100;
        if (k == 1) return "tysiąc";
        if (lastDigit >= 2 && lastDigit <= 4 && !(lastTwo >= 12 && lastTwo <= 14)) return "tysiące";
        return "tysięcy";
    }

    private static String pl(long n) {
        if (n < 1000) return plBelow1000(n);
        long k = n / 1000, r = n % 1000;
        String kw = (k == 1) ? "tysiąc" : plBelow1000(k) + " " + plThousandWord(k);
        return r == 0 ? kw : kw + " " + plBelow1000(r);
    }

    // ---------------------------------------------------------------- Russian
    // Space-separated groups; "тысяча" takes plural forms and feminine unit gender (одна/две).

    private static final String[] RU_0_19 = {
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"
    };
    private static final String[] RU_TENS = {
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят",
        "восемьдесят", "девяносто"
    };
    private static final String[] RU_HUNDREDS = {
        "", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот",
        "восемьсот", "девятьсот"
    };

    /** Unit word 0..19; feminine forms of 1/2 (одна/две) when {@code fem} — used before тысяча. */
    private static String ruUnit(long n, boolean fem) {
        if (fem && n == 1) return "одна";
        if (fem && n == 2) return "две";
        return RU_0_19[(int) n];
    }

    private static String ruBelow100(long n, boolean fem) {
        if (n < 20) return ruUnit(n, fem);
        long t = n / 10, r = n % 10;
        return r == 0 ? RU_TENS[(int) t] : RU_TENS[(int) t] + " " + ruUnit(r, fem);
    }

    private static String ruBelow1000(long n, boolean fem) {
        if (n < 100) return ruBelow100(n, fem);
        long h = n / 100, r = n % 100;
        return RU_HUNDREDS[(int) h] + (r == 0 ? "" : " " + ruBelow100(r, fem));
    }

    /** Russian plural of "тысяча" governed by the thousands count k. */
    private static String ruThousandWord(long k) {
        long lastDigit = k % 10, lastTwo = k % 100;
        if (lastDigit == 1 && lastTwo != 11) return "тысяча";
        if (lastDigit >= 2 && lastDigit <= 4 && !(lastTwo >= 12 && lastTwo <= 14)) return "тысячи";
        return "тысяч";
    }

    private static String ru(long n) {
        if (n < 1000) return ruBelow1000(n, false);
        long k = n / 1000, r = n % 1000;
        // 1000 reads idiomatically as "тысяча" (одна dropped); thousands count is feminine.
        String kw = (k == 1) ? "тысяча" : ruBelow1000(k, true) + " " + ruThousandWord(k);
        return r == 0 ? kw : kw + " " + ruBelow1000(r, false);
    }

    // ---------------------------------------------------------------- Japanese
    // CJK myriad grouping (万). "1" is omitted before 十/百/千 but kept before 万 (一万).

    private static final String[] JA_DIG = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private static String jaBelow10000(long n) {
        long thousands = n / 1000, hundreds = (n / 100) % 10, tens = (n / 10) % 10, ones = n % 10;
        StringBuilder sb = new StringBuilder();
        if (thousands > 0) sb.append(thousands == 1 ? "" : JA_DIG[(int) thousands]).append("千");
        if (hundreds > 0) sb.append(hundreds == 1 ? "" : JA_DIG[(int) hundreds]).append("百");
        if (tens > 0) sb.append(tens == 1 ? "" : JA_DIG[(int) tens]).append("十");
        if (ones > 0) sb.append(JA_DIG[(int) ones]);
        return sb.toString();
    }

    private static String ja(long n) {
        if (n == 0) return "零";
        if (n < 10_000) return jaBelow10000(n);
        long man = n / 10_000, rem = n % 10_000;
        String s = (man == 1 ? "一" : jaBelow10000(man)) + "万";
        return rem == 0 ? s : s + jaBelow10000(rem);
    }

    // ---------------------------------------------------------------- Korean
    // Sino-Korean numerals; "1" is omitted before 십/백/천 and before 만 (10000 = 만).

    private static final String[] KO_DIG = {"", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};

    private static String koBelow10000(long n) {
        long thousands = n / 1000, hundreds = (n / 100) % 10, tens = (n / 10) % 10, ones = n % 10;
        StringBuilder sb = new StringBuilder();
        if (thousands > 0) sb.append(thousands == 1 ? "" : KO_DIG[(int) thousands]).append("천");
        if (hundreds > 0) sb.append(hundreds == 1 ? "" : KO_DIG[(int) hundreds]).append("백");
        if (tens > 0) sb.append(tens == 1 ? "" : KO_DIG[(int) tens]).append("십");
        if (ones > 0) sb.append(KO_DIG[(int) ones]);
        return sb.toString();
    }

    private static String ko(long n) {
        if (n == 0) return "영";
        if (n < 10_000) return koBelow10000(n);
        long man = n / 10_000, rem = n % 10_000;
        String s = (man == 1 ? "" : koBelow10000(man)) + "만";
        return rem == 0 ? s : s + koBelow10000(rem);
    }
}
