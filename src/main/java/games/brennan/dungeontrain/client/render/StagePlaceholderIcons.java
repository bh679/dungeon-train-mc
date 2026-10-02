package games.brennan.dungeontrain.client.render;

import org.jetbrains.annotations.Nullable;

/**
 * Which model draws a stage placeholder's own tile as an <em>item</em>. Most placeholders use their
 * block model, but some block models make no icon: a {@code multipart} blockstate (fence, walls,
 * glass panes) yields no quads without a block state, a button's in-world box is tiny, and a door
 * is two halves. Those get a dedicated model here — the {@code _inventory} shape vanilla uses for
 * fences, walls and buttons, a flat sprite for the door and the panes.
 *
 * <p>Strings only, so the resource test can read it without a client.</p>
 */
public final class StagePlaceholderIcons {

    private StagePlaceholderIcons() {}

    /**
     * The icon model path (under {@code assets/dungeontrain/models/}, no namespace or extension)
     * for placeholder {@code name}, or null when its block model serves as the icon.
     */
    @Nullable
    public static String iconModel(String name) {
        if (name.equals("stage_door") || name.startsWith("stage_glass_pane_")) {
            return "item/" + name + "_sprite";
        }
        if (name.equals("stage_fence") || name.equals("stage_button") || name.equals("stage_wood_button")
            || name.endsWith("_wall")) {
            return "block/" + name + "_inventory";
        }
        return null;
    }
}
