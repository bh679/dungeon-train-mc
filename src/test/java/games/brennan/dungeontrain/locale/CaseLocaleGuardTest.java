package games.brennan.dungeontrain.locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseLocaleGuardTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private Locale base;
    private Locale format;
    private Locale display;

    @BeforeEach
    void remember() {
        base = Locale.getDefault();
        format = Locale.getDefault(Locale.Category.FORMAT);
        display = Locale.getDefault(Locale.Category.DISPLAY);
    }

    @AfterEach
    void restore() {
        Locale.setDefault(base);
        Locale.setDefault(Locale.Category.FORMAT, format);
        Locale.setDefault(Locale.Category.DISPLAY, display);
    }

    @Test
    void turkishLocaleMangledWorldWeaverNamesBeforeTheGuard() {
        Locale.setDefault(TURKISH);
        assertEquals("spıkes_down", "SPIKES_DOWN".toLowerCase());
    }

    @Test
    void turkishLocaleCasesInternalNamesLikeRootAfterTheGuard() {
        Locale.setDefault(TURKISH);

        CaseLocaleGuard.Result result = CaseLocaleGuard.apply();

        assertTrue(result.changed());
        assertEquals(TURKISH, result.original());
        assertEquals("spikes_down", "SPIKES_DOWN".toLowerCase());
        assertEquals("nether_ceil", "NETHER_CEIL".toLowerCase());
        assertEquals("IRON", "iron".toUpperCase());
    }

    @Test
    void turkishFormatAndDisplayLocalesAreKept() {
        Locale.setDefault(TURKISH);

        CaseLocaleGuard.apply();

        assertEquals(TURKISH, Locale.getDefault(Locale.Category.FORMAT));
        assertEquals(TURKISH, Locale.getDefault(Locale.Category.DISPLAY));
        assertEquals("1,5", String.format("%.1f", 1.5));
    }

    @Test
    void azerbaijaniIsGuarded() {
        assertTrue(CaseLocaleGuard.needsGuard(Locale.forLanguageTag("az-AZ")));
    }

    @Test
    void otherLocalesAreLeftAlone() {
        for (String tag : new String[] {"en-US", "de-DE", "ja-JP", "uk-UA"}) {
            Locale locale = Locale.forLanguageTag(tag);
            Locale.setDefault(locale);

            assertFalse(CaseLocaleGuard.apply().changed(), tag);
            assertEquals(locale, Locale.getDefault(), tag);
        }
    }

    @Test
    void applyingTwiceIsANoOp() {
        Locale.setDefault(TURKISH);
        CaseLocaleGuard.apply();

        assertFalse(CaseLocaleGuard.apply().changed());
        assertEquals(Locale.ROOT, Locale.getDefault());
    }
}
