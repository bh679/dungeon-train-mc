package games.brennan.dungeontrain.client.videos;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoAgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    /** {@code today}, {@code ago:<unit-key>:<n>}, or the literal date — the label's shape, locale-free. */
    private static String shape(Component c) {
        if (c == null) return null;
        if (!(c.getContents() instanceof TranslatableContents t)) return c.getString();
        if (t.getKey().endsWith(".today")) return "today";
        Component inner = (Component) t.getArgs()[0];
        TranslatableContents clause = (TranslatableContents) inner.getContents();
        return "ago:" + clause.getKey() + ":" + clause.getArgs()[0];
    }

    private static String at(String day) {
        return shape(VideoAge.label(day, TODAY, "en_us"));
    }

    @Test
    void daysWeeksMonthsThenTheDate() {
        assertEquals("today", at("2026-09-14"));
        assertEquals("ago:chat.dungeontrain.time.day.one:1", at("2026-09-13"));
        assertEquals("ago:chat.dungeontrain.time.day.other:6", at("2026-09-08"));
        assertEquals("ago:chat.dungeontrain.time.week.one:1", at("2026-09-07"), "7 days = 1 week");
        assertEquals("ago:chat.dungeontrain.time.week.other:4", at("2026-08-16"), "29 days = 4 weeks");
        assertEquals("ago:chat.dungeontrain.time.month.one:1", at("2026-08-15"), "30 days = 1 month");
        assertEquals("ago:chat.dungeontrain.time.month.other:2", at("2026-07-11"), "calendar months");
        assertEquals("ago:chat.dungeontrain.time.month.other:11", at("2025-09-15"), "364 days");
        assertEquals("2025-09-14", at("2025-09-14"), "365 days: the date");
        assertEquals("2027-01-01", at("2027-01-01"), "future (clock skew): the date");
    }

    @Test
    void localePicksThePluralForm() {
        assertEquals("ago:chat.dungeontrain.time.day.few:3", shape(VideoAge.label("2026-09-11", TODAY, "ru_ru")));
        assertEquals("ago:chat.dungeontrain.time.day.other:3", shape(VideoAge.label("2026-09-11", TODAY, "zh_cn")));
    }

    @Test
    void missingOrGarbageDayIsNull() {
        assertNull(VideoAge.label(null, TODAY, "en_us"));
        assertNull(VideoAge.label("  ", TODAY, "en_us"));
        assertNull(VideoAge.label("yesterday", TODAY, "en_us"));
    }
}
