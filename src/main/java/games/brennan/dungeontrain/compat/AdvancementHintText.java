package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The fog-of-war masking rules for {@code dungeontrain:*} advancement tooltips, shared by every
 * advancements screen Dungeon Train supports.
 *
 * <p>DT marks almost its whole {@code dungeon_train} tree {@code hidden:true} and reveals a node
 * only once its parent is earned (server-side frontier gate — see
 * {@code AdvancementVisibilityEvaluatorMixin}). A revealed-but-unearned node should therefore show
 * its icon and title, but never its real description: that gets swapped for the advancement's hint,
 * or a {@code ???} placeholder.</p>
 *
 * <p>That policy used to live as {@code @Unique} methods inside the mixin on vanilla's
 * {@code AdvancementWidget}. It moved here when Better Advancements support was added: BA replaces
 * the whole screen with its own widget class, so a second mixin needs the identical rules and the
 * two must not be able to drift apart.</p>
 *
 * <p>Client-side only — {@link #hintOrPlaceholder} resolves against {@link I18n}.</p>
 */
public final class AdvancementHintText {

    /** Shown when an advancement has no {@code .hint} translation of its own. */
    private static final String PLACEHOLDER_KEY = "advancements.dungeontrain.hidden_description";

    private AdvancementHintText() {
    }

    /** True when {@code id} belongs to Dungeon Train. Advancements from other mods are never masked. */
    public static boolean isModAdvancement(ResourceLocation id) {
        return id != null && DungeonTrain.MOD_ID.equals(id.getNamespace());
    }

    /**
     * Whether this advancement's description should be replaced by its hint.
     *
     * <p>Masked: unearned {@code dungeontrain:*} advancements. Never masked: other namespaces, tab
     * roots (the tab needs to explain itself when opened), the {@code editor/} tab (its descriptions
     * document editor capabilities and double as discoverability hints), and anything already
     * earned.</p>
     *
     * @param progress may be null — treated as "not earned yet"
     */
    public static boolean shouldMask(ResourceLocation id, AdvancementProgress progress) {
        if (!isModAdvancement(id)) return false;
        String path = id.getPath();
        if (path.endsWith("/root")) return false;
        if (path.startsWith("editor/")) return false;
        return progress == null || !progress.isDone();
    }

    /**
     * The masked stand-in text: {@code advancements.<namespace>.<path>.hint} with slashes mapped to
     * dots (e.g. {@code dungeon_train/track_record} →
     * {@code advancements.dungeontrain.dungeon_train.track_record.hint}), falling back to the shared
     * {@code ???} placeholder when no such translation exists.
     */
    public static Component hintOrPlaceholder(ResourceLocation id) {
        String key = "advancements." + id.getNamespace() + "."
            + id.getPath().replace('/', '.') + ".hint";
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.translatable(PLACEHOLDER_KEY);
    }
}
