package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.menu.EditorTemplateJump;

import java.util.Locale;

/**
 * Sending the player to a build that has just been loaded, in the words this screen thinks in.
 *
 * <p>{@link EditorTemplateJump} answers in relay kinds because that is what a download holds; this
 * is the thin layer that asks it the two questions the creator pane has — is there anywhere to go,
 * and go there — without the pane having to know about editor category ids.</p>
 */
public final class EditorTemplateJumpBridge {

    private EditorTemplateJumpBridge() {}

    /** Whether the editor has a plot for this kind at all — false for a carriage group. */
    public static boolean hasHome(BuilderPhotoPaths.Kind kind, String subKind) {
        return EditorTemplateJump.categoryIdFor(kind, subKind) != null;
    }

    /** Walk there, switching editor category only when it is not the one being stood in. */
    public static boolean go(BuilderPhotoPaths.Kind kind, String id, String subKind) {
        return EditorTemplateJump.go(kind, id, subKind,
            EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT));
    }
}
