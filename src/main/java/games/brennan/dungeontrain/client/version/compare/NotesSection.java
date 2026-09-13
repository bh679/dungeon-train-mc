package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.shaders.ShaderDetailPane;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * One tab's worth of release notes: a title ("v0.854.0", or a companion mod's name) and its
 * lines. The Versions page shows every section of the selected row concatenated in its notes
 * box; the fullscreen view gives each one a tab.
 */
public record NotesSection(Component title, List<ShaderDetailPane.Line> lines) {

    public static NotesSection forEntry(ReleaseEntry entry) {
        return new NotesSection(Component.literal("v" + entry.version()), ChangelogLines.forEntry(entry));
    }

    /** Every section's lines in order — what the page's own notes box shows. */
    public static List<ShaderDetailPane.Line> flatten(List<NotesSection> sections) {
        return sections.stream().flatMap(s -> s.lines().stream()).toList();
    }
}
