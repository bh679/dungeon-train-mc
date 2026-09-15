package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.LifeDisqualification;
import games.brennan.dungeontrain.client.LifeDisqualificationClient;
import games.brennan.dungeontrain.client.TrackedAdvancements;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

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
    /** The red line under the hint while this life has ruled the advancement out. */
    private static final String DISQUALIFIED_KEY = "advancements.dungeontrain.disqualified_this_life";
    /** Tooltip footer on a trackable, unearned advancement: click to track / click to stop tracking. */
    private static final String TRACK_CLICK_KEY = "advancements.dungeontrain.track.click";
    private static final String TRACK_TRACKING_KEY = "advancements.dungeontrain.track.tracking";

    /** The advancements a single action can rule out for a life — the only ones worth tracking. */
    private static final Set<ResourceLocation> TRACKABLE = Set.copyOf(LifeDisqualification.disqualifiableIds());

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

    /** True for an advancement the server can rule out for a life (and so the player may track). */
    public static boolean isTrackable(ResourceLocation id) {
        return id != null && TRACKABLE.contains(id);
    }

    /** Whether {@code id} should draw greyed out: a mod advancement this life has ruled out, not yet earned. */
    public static boolean isGreyedOut(ResourceLocation id, AdvancementProgress progress) {
        if (!isModAdvancement(id)) return false;
        if (progress != null && progress.isDone()) return false;
        return LifeDisqualificationClient.isDisqualified(id);
    }

    /**
     * Cache key for a widget's masked description: changes whenever the disqualified mirror or the
     * tracked set changes, so a tooltip split for one state is never shown in another.
     */
    public static int maskedDescriptionRevision() {
        return LifeDisqualificationClient.revision() * 31 + TrackedAdvancements.revision();
    }

    /**
     * The full masked tooltip body for an unearned advancement: the hint (or placeholder), then a
     * red "not this life" line if this life has ruled it out, then a grey track/tracking footer if
     * it is trackable. Callers have already passed {@link #shouldMask}.
     */
    public static Component maskedDescription(ResourceLocation id) {
        MutableComponent text = hintOrPlaceholder(id).copy();
        if (LifeDisqualificationClient.isDisqualified(id)) {
            text.append("\n").append(Component.translatable(DISQUALIFIED_KEY).withStyle(ChatFormatting.RED));
        }
        if (isTrackable(id)) {
            String key = TrackedAdvancements.isTracked(id) ? TRACK_TRACKING_KEY : TRACK_CLICK_KEY;
            text.append("\n").append(Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY));
        }
        return text;
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
