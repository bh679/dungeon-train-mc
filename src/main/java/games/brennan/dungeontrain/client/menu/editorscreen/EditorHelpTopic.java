package games.brennan.dungeontrain.client.menu.editorscreen;

import java.util.ArrayList;
import java.util.List;

/**
 * The Help tab's topics, in list order: one screen per editor system.
 *
 * <p>Each topic is a title and a handful of paragraphs, every one its own lang key so translators
 * see sentences rather than a wall. The paragraph count lives here, not in the lang file, so a
 * missing key is a test failure ({@code EditorHelpTopicTest}) rather than a raw key on screen.</p>
 */
public enum EditorHelpTopic {
    CARRIAGES("carriages", 4),
    CONTENTS("contents", 4),
    TRACKS_DIMENSIONS("tracks_dimensions", 4),
    EDITOR_MENU("editor_menu", 5),
    BLOCK_VARIANTS("block_variants", 5),
    CONTAINER_VARIANTS("container_variants", 5),
    STAGES("stages", 4),
    NEW_BLOCKS("new_blocks", 5);

    private final String id;
    private final int paragraphs;

    EditorHelpTopic(String id, int paragraphs) {
        this.id = id;
        this.paragraphs = paragraphs;
    }

    public String id() {
        return id;
    }

    /** The row label and the header of the topic's screen. */
    public String titleKey() {
        return EditorScreenLang.HELP_PREFIX + id + ".title";
    }

    /** The body, one key per paragraph: {@code …help.<id>.p1}, {@code .p2}, … */
    public List<String> paragraphKeys() {
        List<String> out = new ArrayList<>(paragraphs);
        for (int i = 1; i <= paragraphs; i++) {
            out.add(EditorScreenLang.HELP_PREFIX + id + ".p" + i);
        }
        return out;
    }
}
