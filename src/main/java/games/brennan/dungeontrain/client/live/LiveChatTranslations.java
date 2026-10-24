package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The pure rules behind a Live chat line's translation: which language this reader reads, which of the
 * relay's translations is theirs, and how it reads in chat. No Minecraft state — everything comes in as
 * arguments. {@link LiveChatBridge} decides when; {@code LiveFeedClientConfig.liveChatTranslation()}
 * decides where (under the original, instead of it, or first).
 *
 * <p>The relay translates only while the Live Translations community reward is on, into the languages
 * its readers report on each poll ({@code lang=}), a poll or two after the line.</p>
 */
public final class LiveChatTranslations {

    public static final String TRANSLATION_KEY = "chat.dungeontrain.live_chat.translation";
    public static final String TRANSLATION_NAMED_KEY = "chat.dungeontrain.live_chat.translation.named";
    public static final String ORIGINAL_KEY = "chat.dungeontrain.live_chat.translation.original";
    public static final String ON_KEY = "chat.dungeontrain.live_chat.translation.on";
    public static final String PATH_KEY = "chat.dungeontrain.live_chat.translation.path";

    /** A Minecraft locale code as the relay expects it: {@code zh_cn}. */
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}_[a-z0-9]{2,4}");

    private LiveChatTranslations() {}

    /**
     * This reader's language for the relay: {@code mc.options.languageCode} lower-cased with its region
     * joined by an underscore ({@code zh_cn}); null when it is not a locale code at all.
     */
    @Nullable
    public static String readerLang(@Nullable String languageCode) {
        if (languageCode == null) return null;
        String code = languageCode.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return LOCALE.matcher(code).matches() ? code : null;
    }

    /**
     * The translation for {@code readerLang} out of a line's map: the exact locale, else one in the same
     * language ({@code pt_pt} reads {@code pt_br} sooner than nothing), else null.
     */
    @Nullable
    public static String pick(@Nullable Map<String, String> tr, @Nullable String readerLang) {
        if (tr == null || readerLang == null) return null;
        String exact = tr.get(readerLang);
        if (exact != null && !exact.isBlank()) return exact;
        String family = readerLang.substring(0, readerLang.indexOf('_') + 1);
        for (Map.Entry<String, String> e : tr.entrySet()) {
            if (e.getKey().startsWith(family) && !e.getValue().isBlank()) return e.getValue();
        }
        return null;
    }

    /**
     * The grey line under the original: {@code ↳ text}, or {@code ↳ Name: text} when another line was
     * printed in between ({@code adjacent} false), so the reader knows whose it is.
     */
    public static MutableComponent underLine(ChatLine line, String translation, boolean adjacent) {
        String text = LiveChatRules.clean(translation, LiveChatRules.MAX_CONTENT);
        if (adjacent) return Component.translatable(TRANSLATION_KEY, Component.literal(text)).withStyle(ChatFormatting.GRAY);
        return Component.translatable(TRANSLATION_NAMED_KEY,
            Component.literal(LiveChatRules.clean(line.author(), LiveChatRules.MAX_AUTHOR)), Component.literal(text))
            .withStyle(ChatFormatting.GRAY);
    }

    /**
     * The line with its translation in place of the text, the original on hover — the same prefix, icon
     * and coloured name as {@link LiveChatRules#line}.
     */
    public static MutableComponent replacedLine(ChatLine line, String translation) {
        Component original = Component.translatable(ORIGINAL_KEY,
            Component.literal(LiveChatRules.clean(line.content(), LiveChatRules.MAX_CONTENT)));
        Style hover = Style.EMPTY.withColor(ChatFormatting.WHITE).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, original));
        return LiveChatRules.line(line, translation, hover);
    }

    /** The language's own name for the one-time "being translated into …" notice: 中文, Deutsch, …; the code itself when unknown. */
    public static String langName(String readerLang) {
        int cut = readerLang.indexOf('_');
        Locale locale = Locale.forLanguageTag(cut < 0 ? readerLang : readerLang.substring(0, cut) + "-" + readerLang.substring(cut + 1).toUpperCase(Locale.ROOT));
        String name = locale.getDisplayLanguage(locale);
        return name.isBlank() || name.equalsIgnoreCase(locale.getLanguage()) ? readerLang : name;
    }
}
