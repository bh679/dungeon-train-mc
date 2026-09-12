package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The release notes' markdown, rendered as the coloured paragraphs {@link ShaderDetailPane}
 * draws. The notes use a deliberately small dialect — {@code ###} version headings, a
 * {@code **bold**} feature title, {@code -} bullets, plain paragraphs — so this is a line
 * classifier, not a markdown engine. Anything it does not recognise is shown as plain text with
 * its markers stripped, never dropped.
 */
public final class ChangelogLines {

    static final int COLOUR_HEADING = 0xFFCC44;
    static final int COLOUR_TITLE = 0xFFFFFF;
    static final int COLOUR_BULLET = 0xC8C8C8;
    static final int COLOUR_BODY = 0xE0E0E0;
    static final int COLOUR_MUTED = 0x909090;

    private static final String BULLET = "• ";
    private static final String TAG_SEPARATOR = " · ";

    private ChangelogLines() {}

    /** One version's notes under a {@code v<version>} heading. */
    public static List<ShaderDetailPane.Line> forEntry(ReleaseEntry entry) {
        List<ShaderDetailPane.Line> out = new ArrayList<>();
        out.add(heading("v" + entry.version()));
        if (entry.hasChangelog()) {
            out.addAll(fromMarkdown(entry.changelog()));
        } else {
            out.add(new ShaderDetailPane.Line(
                    Component.translatable("gui.dungeontrain.version.compare.nolog"), COLOUR_MUTED));
        }
        return out;
    }

    /**
     * One release's notes from the curated ledger, under a {@code v<release>} heading: per entry a
     * bold title, its tags in a muted line, the summary, then bullets. The same lines the markdown
     * path produces for the same entry, plus the tag line — so a release read either way looks alike.
     */
    public static List<ShaderDetailPane.Line> forLedgerEntries(FullSemver release, List<LedgerEntry> entries) {
        List<ShaderDetailPane.Line> out = new ArrayList<>();
        out.add(heading("v" + release));
        for (LedgerEntry entry : entries) {
            if (!entry.title().isBlank()) {
                out.add(new ShaderDetailPane.Line(
                        Component.literal(entry.title()).withStyle(ChatFormatting.BOLD), COLOUR_TITLE));
            }
            tagLine(entry).ifPresent(out::add);
            if (!entry.summary().isBlank()) {
                out.add(new ShaderDetailPane.Line(Component.literal(entry.summary()), COLOUR_BODY));
            }
            for (String h : entry.highlights()) {
                out.add(new ShaderDetailPane.Line(Component.literal(BULLET + h), COLOUR_BULLET));
            }
        }
        return out;
    }

    /** "New Feature · Editor" — the entry's tags in display order, or nothing for an untagged one. */
    static Optional<ShaderDetailPane.Line> tagLine(LedgerEntry entry) {
        MutableComponent text = null;
        for (ChangelogTag tag : ChangelogTag.values()) {
            if (!entry.tags().contains(tag)) continue;
            if (text == null) {
                text = Component.empty();
            } else {
                text.append(TAG_SEPARATOR);
            }
            text.append(tag.label());
        }
        return text == null ? Optional.empty() : Optional.of(new ShaderDetailPane.Line(text, COLOUR_MUTED));
    }

    /** Several versions' notes in the order given, each under its own heading. */
    public static List<ShaderDetailPane.Line> forEntries(List<ReleaseEntry> entries) {
        List<ShaderDetailPane.Line> out = new ArrayList<>();
        for (ReleaseEntry entry : entries) {
            out.addAll(forEntry(entry));
        }
        return out;
    }

    static List<ShaderDetailPane.Line> fromMarkdown(String markdown) {
        List<ShaderDetailPane.Line> out = new ArrayList<>();
        for (String raw : markdown.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                String text = line.replaceFirst("^#+\\s*", "");
                // The notes already open with "### <version>", which the entry heading has said.
                if (FullSemver.parse(text).isEmpty()) {
                    out.add(heading(stripInline(text)));
                }
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                out.add(new ShaderDetailPane.Line(
                        Component.literal(BULLET + stripInline(line.substring(2))), COLOUR_BULLET));
            } else if (line.startsWith("**") && line.endsWith("**") && line.length() > 4) {
                out.add(new ShaderDetailPane.Line(
                        Component.literal(stripInline(line)).withStyle(ChatFormatting.BOLD), COLOUR_TITLE));
            } else {
                out.add(new ShaderDetailPane.Line(Component.literal(stripInline(line)), COLOUR_BODY));
            }
        }
        return out;
    }

    private static ShaderDetailPane.Line heading(String text) {
        return heading(Component.literal(text));
    }

    /** A gold, bold heading line — the same weight the version headings get. */
    static ShaderDetailPane.Line heading(Component text) {
        return new ShaderDetailPane.Line(text.copy().withStyle(ChatFormatting.BOLD), COLOUR_HEADING);
    }

    /** Drop the inline markers we do not render: bold, italics, code, and link syntax. */
    static String stripInline(String s) {
        return s.replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
    }
}
