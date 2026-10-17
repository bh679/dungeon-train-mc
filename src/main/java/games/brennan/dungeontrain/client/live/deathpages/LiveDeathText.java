package games.brennan.dungeontrain.client.live.deathpages;

import games.brennan.dungeontrain.client.DeathScreenText;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Text helpers for the Live Eye death pages: lang keys, figures in white, durations and dates. */
final class LiveDeathText {

    static final String KEY = "gui.dungeontrain.death.live.";

    private LiveDeathText() {}

    static Component tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    static String str(String key, Object... args) {
        return tr(key, args).getString();
    }

    /** A figure wrapped so the narration draws it white ({@link DeathScreenText#styled}). */
    static String num(Object figure) {
        return DeathScreenText.NUM_START + String.valueOf(figure) + DeathScreenText.NUM_END;
    }

    /** "1 h 12 m", "48 m", "40 s": how long something lasted, in words the narration can carry. */
    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return str("dur.hm", s / 3600, String.format(Locale.ROOT, "%02d", (s % 3600) / 60));
        if (s >= 60) return str("dur.m", s / 60);
        return str("dur.s", s);
    }

    /** "1:12": hours and minutes for a tile. */
    static String clock(long ms) {
        long m = Math.max(0, ms / 60_000);
        return String.format(Locale.ROOT, "%d:%02d", m / 60, m % 60);
    }

    /** "16 Nov" in the player's language. */
    static String date(long epochMs) {
        return DateTimeFormatter.ofPattern("d MMM", locale()).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }

    /** "14:02" in the player's own time zone. */
    static String time(long epochMs) {
        return DateTimeFormatter.ofPattern("HH:mm", locale()).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }

    private static Locale locale() {
        try {
            String code = Minecraft.getInstance().options.languageCode;
            return code == null || code.isEmpty() ? Locale.ROOT : Locale.forLanguageTag(code.replace('_', '-'));
        } catch (RuntimeException e) {
            return Locale.ROOT;
        }
    }
}
