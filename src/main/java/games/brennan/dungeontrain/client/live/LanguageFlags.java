package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The small flag on a translated Live chat line, showing the language it came from. One glyph per flag in
 * {@link #FONT}; {@code scripts/language-flags/make-flags.py} draws them and writes the font, in {@link #CODES}'s
 * order. A flag stands for a language here, not a country: its hover shows the original message.
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

    /** The most flags a line of the reader's own ends with; the other languages become {@code +N}. */
    static final int MAX_REACH_FLAGS = 3;

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

    /**
     * The flag for {@code src} with {@code original}, the untranslated message, on hover; white so its own colours
     * show; null when there is none. The hover text is a new object each call: {@link LiveChatFlagHover} tells flags apart by it.
     */
    @Nullable
    public static MutableComponent flag(@Nullable String src, String original) {
        String code = codeOf(src);
        if (code == null) return null;
        Component name = Component.literal(LiveChatRules.clean(original, LiveChatRules.MAX_CONTENT));
        return Component.literal(String.valueOf((char) (FIRST_GLYPH + CODES.indexOf(code))))
            .withStyle(s -> s.withFont(FONT).withColor(ChatFormatting.WHITE)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, name)));
    }

    /**
     * The languages a line was translated into out of the relay's {@code tr}, sorted so the flags stay put as more
     * arrive: the first {@link #MAX_REACH_FLAGS} with a flag in {@code flagged}, every other one in {@code rest}. One
     * per flag, and neither the line's own language nor the reader's.
     */
    record Reach(List<String> flagged, List<String> rest) {}

    static Reach reachOf(@Nullable Map<String, String> tr, @Nullable String src, @Nullable String reader) {
        if (tr == null) return new Reach(List.of(), List.of());
        String srcCode = codeOf(src);
        String readerCode = codeOf(reader);
        List<String> flagged = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String tag : new TreeSet<>(tr.keySet())) {
            String code = codeOf(tag);
            if (code == null) {
                rest.add(tag);
            } else if (!code.equals(srcCode) && !code.equals(readerCode) && seen.add(code)) {
                (flagged.size() < MAX_REACH_FLAGS ? flagged : rest).add(tag);
            }
        }
        return new Reach(List.copyOf(flagged), List.copyOf(rest));
    }

    /**
     * What ends a line of the reader's own: a flag per language it reached with that translation on hover, then
     * {@code +N} naming the others on hover; null when it reached none. Not {@link LiveChatFlagHover}'s to swap.
     */
    @Nullable
    static MutableComponent reach(@Nullable Map<String, String> tr, @Nullable String src, @Nullable String reader) {
        Reach reach = reachOf(tr, src, reader);
        if (reach.flagged().isEmpty() && reach.rest().isEmpty()) return null;
        MutableComponent out = Component.empty();
        for (String tag : reach.flagged()) {
            out.append(flag(tag, tr.get(tag))).append(" ");
        }
        if (reach.rest().isEmpty()) return out;
        String names = reach.rest().stream().map(LiveChatTranslations::langName).collect(Collectors.joining(", "));
        return out.append(Component.literal("+" + reach.rest().size()).withStyle(s -> s.withColor(ChatFormatting.GRAY)
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(names)))));
    }
}
