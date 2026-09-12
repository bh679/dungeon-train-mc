package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

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
        return new ShaderDetailPane.Line(Component.literal(text).withStyle(ChatFormatting.BOLD), COLOUR_HEADING);
    }

    /** Drop the inline markers we do not render: bold, italics, code, and link syntax. */
    static String stripInline(String s) {
        return s.replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
    }
}
