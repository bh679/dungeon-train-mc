package games.brennan.dungeontrain.client.chat.emoji;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes Discord-style emoji draw in chat the way Discord draws them.
 *
 * <ul>
 *   <li>Unicode emoji (ZWJ families, skin tones, flags, keycaps) become one character of the bundled
 *       Twemoji font {@code dungeontrain:emoji} ({@link EmojiIndex}).</li>
 *   <li>Custom server emoji {@code <:name:id>} / {@code <a:name:id>} draw their image once
 *       {@link DiscordEmojiCache} has it; until then a grey {@code :name:} stands in, the fetch starts,
 *       and the id is reported in {@link Result#pendingIds()} so the caller can re-draw the line later.</li>
 *   <li>A raw Discord mention {@code <@id>} reads {@code @dev} (the only person DP pings).</li>
 *   <li>{@code :shortcode:} typed in game becomes the emoji before it is sent ({@link #expandShortcodes}).</li>
 * </ul>
 *
 * <p>Styles, click and hover events of the original are kept; emoji are drawn white so their colours
 * aren't tinted by the text colour. A component with nothing to change comes back as the same instance.</p>
 */
public final class EmojiText {

    public static final ResourceLocation EMOJI_FONT = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "emoji");
    public static final ResourceLocation DISCORD_EMOJI_FONT =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "discord_emoji");

    private static final Pattern CUSTOM = Pattern.compile("<(a?):([A-Za-z0-9_~]{1,32}):(\\d{5,25})>");
    private static final Pattern MENTION = Pattern.compile("<@!?\\d+>");
    private static final Pattern SHORTCODE = Pattern.compile(":([a-z0-9_+\\-]{1,40}):", Pattern.CASE_INSENSITIVE);
    private static final TextColor WHITE = TextColor.fromLegacyFormat(ChatFormatting.WHITE);

    /** Where custom emoji images come from; the cache in game, a stub in tests. */
    public interface CustomSource {
        OptionalInt slot(String id);

        void request(String id);

        CustomSource CACHE = new CustomSource() {
            @Override
            public OptionalInt slot(String id) {
                return DiscordEmojiCache.slot(id);
            }

            @Override
            public void request(String id) {
                DiscordEmojiCache.request(id);
            }
        };
    }

    /** The converted text, and the custom-emoji ids it is still waiting on. */
    public record Result(Component component, Set<String> pendingIds) {}

    private EmojiText() {}

    public static Result convert(Component in) {
        return convert(in, EmojiIndex.get(), CustomSource.CACHE);
    }

    public static Result convert(Component in, EmojiIndex index, CustomSource custom) {
        List<Object[]> parts = new ArrayList<>();
        boolean[] work = {false};
        in.visit((style, text) -> {
            parts.add(new Object[]{style, text});
            if (!work[0] && mayContain(text)) work[0] = true;
            return Optional.empty();
        }, Style.EMPTY);
        if (!work[0]) return new Result(in, Set.of());
        MutableComponent out = Component.empty();
        Set<String> pending = new HashSet<>();
        boolean changed = false;
        for (Object[] p : parts) {
            changed |= appendConverted(out, (String) p[1], (Style) p[0], index, custom, pending, false);
        }
        return changed ? new Result(out, Set.copyOf(pending)) : new Result(in, Set.of());
    }

    /** Just the plain string's conversion (menu chat, reaction chips), in {@code style}. */
    public static MutableComponent convert(String text, Style style, boolean force) {
        MutableComponent out = Component.empty();
        appendConverted(out, text, style, EmojiIndex.get(), CustomSource.CACHE, new HashSet<>(), force);
        return out;
    }

    /**
     * One reaction's emoji: a custom one by id (its image, or {@code :name:} while loading), else the
     * unicode name drawn from the Twemoji font even without its variation selector.
     */
    public static MutableComponent reactionEmoji(String name, @Nullable String id, Style style) {
        if (id != null && !id.isBlank()) {
            OptionalInt slot = DiscordEmojiCache.slot(id);
            if (slot.isPresent()) return glyph(slot.getAsInt(), DISCORD_EMOJI_FONT, style);
            DiscordEmojiCache.request(id);
            return Component.literal(":" + name + ":").withStyle(style);
        }
        return convert(name, style, true);
    }

    /** {@code :shortcode:} → emoji for text about to be sent; unknown names are left alone. */
    public static String expandShortcodes(String text) {
        if (text == null || text.indexOf(':') < 0) return text;
        EmojiIndex index = EmojiIndex.get();
        Matcher m = SHORTCODE.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            String emoji = index.shortcode(m.group(1));
            if (emoji == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            any = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(emoji));
        }
        if (!any) return text;
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean mayContain(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c >= 0x00A9) return true;
        }
        return false;
    }

    /** Appends {@code text} to {@code out}, emoji swapped in. Returns whether anything changed. */
    private static boolean appendConverted(MutableComponent out, String text, Style style, EmojiIndex index,
                                           CustomSource custom, Set<String> pending, boolean force) {
        StringBuilder run = new StringBuilder();
        boolean changed = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                Matcher cm = CUSTOM.matcher(text).region(i, text.length());
                if (cm.lookingAt()) {
                    flush(out, run, style);
                    String name = cm.group(2);
                    String id = cm.group(3);
                    OptionalInt slot = custom.slot(id);
                    if (slot.isPresent()) {
                        out.append(glyph(slot.getAsInt(), DISCORD_EMOJI_FONT, style));
                    } else {
                        custom.request(id);
                        pending.add(id);
                        out.append(Component.literal(":" + name + ":").withStyle(style.withColor(ChatFormatting.GRAY)));
                    }
                    i = cm.end();
                    changed = true;
                    continue;
                }
                Matcher mm = MENTION.matcher(text).region(i, text.length());
                if (mm.lookingAt()) {
                    run.append("@dev");
                    i = mm.end();
                    changed = true;
                    continue;
                }
            }
            if (c >= 0x00A9) {
                EmojiIndex.Match match = index.match(text, i, force);
                if (match != null) {
                    flush(out, run, style);
                    out.append(glyph(match.glyph(), EMOJI_FONT, style));
                    i += match.length();
                    changed = true;
                    continue;
                }
            }
            run.append(c);
            i++;
        }
        flush(out, run, style);
        return changed;
    }

    private static MutableComponent glyph(int codepoint, ResourceLocation font, Style style) {
        return Component.literal(new String(Character.toChars(codepoint)))
            .withStyle(style.withFont(font).withColor(WHITE).withItalic(false));
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style) {
        if (run.isEmpty()) return;
        out.append(Component.literal(run.toString()).withStyle(style));
        run.setLength(0);
    }
}
