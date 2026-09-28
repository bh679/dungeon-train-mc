package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;

/**
 * Which template the screen means — the selection, and the plot the player stands in.
 *
 * <p>Same vocabulary the commands use: {@code modelId} is the command token (the id for carriages
 * and contents, the kind for parts and track-side kinds) and {@code modelName} the variant name.
 * A sub-variant also names its {@code parentId}, since it is addressed through the parent's
 * group.</p>
 *
 * @param parentId the group parent for a sub-variant, or {@code ""} for a top-level template
 */
public record VariantKey(PlotCategory category, String modelId, String modelName, String parentId) {

    public VariantKey {
        modelId = modelId == null ? "" : modelId;
        modelName = modelName == null ? "" : modelName;
        parentId = parentId == null ? "" : parentId;
    }

    /** A top-level template. */
    public static VariantKey of(PlotCategory category, String modelId, String modelName) {
        return new VariantKey(category, modelId, modelName, "");
    }

    /** The key a roster row answers to. */
    public static VariantKey of(EditorTypeMenusPacket.Variant v, String parentId) {
        return new VariantKey(v.plotCategory(), v.modelId(), v.modelName(), parentId);
    }

    public boolean isSubVariant() {
        return !parentId.isEmpty();
    }

    /** The id a command names for this template: the name for track-side kinds, the id otherwise. */
    public String displayName() {
        return modelName.isEmpty() ? modelId : modelName;
    }

    /**
     * The plot the player stands in, from the status HUD's fields, or null outside a plot.
     *
     * <p>Parts report their model as {@code kind:name} in one field, so that is split back into
     * the kind token and the name the part commands take.</p>
     */
    public static VariantKey fromStatus(String categoryId, String modelId, String modelName) {
        PlotCategory category = PlotCategory.fromId(categoryId).orElse(null);
        if (category == null || modelId == null || modelId.isEmpty()) return null;
        if (category == PlotCategory.PARTS) {
            int colon = modelId.indexOf(':');
            if (colon > 0) {
                return new VariantKey(category, modelId.substring(0, colon), modelId.substring(colon + 1), "");
            }
        }
        return new VariantKey(category, modelId, modelName, "");
    }

    /** True when {@code other} names the same template, ignoring the parent link. */
    public boolean sameTemplate(VariantKey other) {
        return other != null && category == other.category
            && modelId.equals(other.modelId) && modelName.equals(other.modelName);
    }
}
