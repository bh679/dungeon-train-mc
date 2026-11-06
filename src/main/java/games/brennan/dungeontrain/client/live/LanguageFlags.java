package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The small flag on a translated Live chat line, showing the language it came from. One glyph per flag in
 * {@link #FONT}; {@code scripts/language-flags/make-flags.py} draws them and writes the font, in {@link #CODES}'s
 * order. A flag stands for a language here, not a country: its hover names the language.
 */
public final class LanguageFlags {

    /** {@code assets/dungeontrain/font/language_flags.json}. */
    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "language_flags");
    static final char FIRST_GLYPH = '';

    /** The font's order: only ever add at the end, matching make-flags.py. */
    static final List<String> CODES = List.of(
        "gb", "es", "mx", "de", "fr", "it", "nl", "pl", "pt", "br", "ro", "ru", "ua", "th", "vn", "cn", "tw",
        "jp", "kr", "id", "my", "ph", "sa", "tr", "in", "se", "no", "dk", "fi", "cz", "hu", "gr", "il", "bg");

    /** A language's flag, where it is not the language's own code. */
    private static final Map<String, String> BY_LANGUAGE = Map.ofEntries(
        Map.entry("en", "gb"), Map.entry("uk", "ua"), Map.entry("vi", "vn"), Map.entry("zh", "cn"), Map.entry("ja", "jp"),
        Map.entry("ko", "kr"), Map.entry("ms", "my"), Map.entry("fil", "ph"), Map.entry("tl", "ph"), Map.entry("ar", "sa"),
        Map.entry("hi", "in"), Map.entry("sv", "se"), Map.entry("nb", "no"), Map.entry("nn", "no"), Map.entry("da", "dk"),
        Map.entry("cs", "cz"), Map.entry("el", "gr"), Map.entry("he", "il"), Map.entry("iw", "il"));

    private LanguageFlags() {}

    /** The flag code for a language tag ({@code es}, {@code zh-TW}, {@code pt_br}), or null when there is none. */
    @Nullable
    static String codeOf(@Nullable String tag) {
        if (tag == null) return null;
        String t = tag.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        String language = t.contains("-") ? t.substring(0, t.indexOf('-')) : t;
        if (language.equals("zh") && (t.contains("tw") || t.contains("hk") || t.contains("hant"))) return "tw";
        if (language.equals("pt") && t.contains("br")) return "br";
        if (language.equals("es") && t.contains("mx")) return "mx";
        String code = BY_LANGUAGE.getOrDefault(language, language);
        return CODES.contains(code) ? code : null;
    }

    /** The language's name in the reader's language: {@code Spanish}, {@code スペイン語}; the tag when unknown. */
    static String nameOf(String tag, @Nullable String readerLang) {
        Locale language = Locale.forLanguageTag(tag.strip().replace('_', '-'));
        Locale reader = readerLang == null ? Locale.ROOT : Locale.forLanguageTag(readerLang.replace('_', '-'));
        String name = language.getDisplayLanguage(reader);
        return name.isBlank() ? tag : name;
    }

    /** The flag for {@code src} with its language's name on hover, white so its own colours show; null when there is none. */
    @Nullable
    public static MutableComponent flag(@Nullable String src, @Nullable String readerLang) {
        String code = codeOf(src);
        if (code == null) return null;
        Component name = Component.literal(nameOf(src, readerLang));
        return Component.literal(String.valueOf((char) (FIRST_GLYPH + CODES.indexOf(code))))
            .withStyle(s -> s.withFont(FONT).withColor(ChatFormatting.WHITE)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, name)));
    }
}
