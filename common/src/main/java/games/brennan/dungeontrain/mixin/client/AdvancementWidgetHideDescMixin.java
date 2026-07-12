package games.brennan.dungeontrain.mixin.client;
import games.brennan.dungeontrain.DtCore;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Hides the description text in the {@link AdvancementWidget} hover
 * tooltip for unearned {@code dungeontrain:*} child advancements while
 * keeping their icon and title visible.
 *
 * <p>Mechanism: every {@code drawHover} access of {@code this.description}
 * is wrapped by {@link ModifyExpressionValue}. When the advancement is in
 * the mod's namespace AND is not yet earned, the wrapper substitutes the
 * advancement's own hint ({@code advancements.<namespace>.<path>.hint},
 * pre-wrapped to the widget's render width) — or a shared {@code ???}
 * placeholder when no hint translation exists — for the real description.
 * Earned advancements and non-mod advancements get the unmodified
 * description.</p>
 *
 * <p>Roots are skipped (path ends in {@code /root}) so the tab opens
 * with a visible explanation of what the tab is.</p>
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementWidgetHideDescMixin {

    @Shadow @Final private AdvancementNode advancementNode;

    @Shadow private AdvancementProgress progress;

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Final private int width;

    /**
     * Lazily-cached hint/placeholder description, computed on first hide so we
     * don't re-split a Component every frame. Each widget instance maps to a
     * single advancement, so this per-instance cache holds that advancement's
     * resolved hint (or the {@code ???} fallback). Width-bound to {@link #width}
     * (same as the real description) so layout calculations stay consistent.
     */
    @Unique
    private List<FormattedCharSequence> dungeontrain$hiddenDesc;

    @ModifyExpressionValue(
        method = "drawHover",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementWidget;description:Ljava/util/List;")
    )
    private List<FormattedCharSequence> dungeontrain$swapDescription(List<FormattedCharSequence> original) {
        if (!dungeontrain$shouldHideDescription()) return original;
        return dungeontrain$getHiddenDesc();
    }

    /**
     * Treat a {@code hidden:true} {@code dungeontrain:*} advancement as visible
     * for tile rendering ({@code draw}) and hover hit-testing
     * ({@code isMouseOver}) once it is on screen at all. Vanilla gates both on
     * {@code isHidden()}: a hidden advancement draws no icon and can't be
     * hovered until earned. But DT hides most of its tree only to reveal a
     * node's children when that node is earned (server-side frontier gate), so
     * any DT widget that exists client-side has already been cleared for
     * display. Forcing the guard's {@code isHidden()} to {@code false} for mod
     * advancements lets the frontier draw with its real icon and show its hover
     * (its description stays masked to a hint via the swap above). Non-mod
     * advancements are untouched.
     */
    @ModifyExpressionValue(
        method = {"draw", "isMouseOver"},
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/advancements/DisplayInfo;isHidden()Z")
    )
    private boolean dungeontrain$revealRevealedIcon(boolean original) {
        if (!original) return false;
        if (advancementNode == null) return original;
        return DtCore.MOD_ID.equals(advancementNode.holder().id().getNamespace()) ? false : original;
    }

    @Unique
    private boolean dungeontrain$shouldHideDescription() {
        if (advancementNode == null) return false;
        ResourceLocation id = advancementNode.holder().id();
        if (!DtCore.MOD_ID.equals(id.getNamespace())) return false;
        String path = id.getPath();
        if (path.endsWith("/root")) return false;
        // Editor tab: descriptions stay visible — they document the
        // editor's capabilities and double as discoverability hints.
        if (path.startsWith("editor/")) return false;
        // Earned → reveal.
        return progress == null || !progress.isDone();
    }

    @Unique
    private List<FormattedCharSequence> dungeontrain$getHiddenDesc() {
        if (dungeontrain$hiddenDesc == null) {
            dungeontrain$hiddenDesc = minecraft.font.split(dungeontrain$hintOrPlaceholder(), width);
        }
        return dungeontrain$hiddenDesc;
    }

    /**
     * The hint shown in place of the hidden description. Looks up
     * {@code advancements.<namespace>.<path>.hint} (path slashes mapped to
     * dots, e.g. {@code dungeon_train/track_record} →
     * {@code advancements.dungeontrain.dungeon_train.track_record.hint}); if
     * no such translation is present, falls back to the shared {@code ???}
     * placeholder. Callers only reach this once
     * {@link #dungeontrain$shouldHideDescription()} has confirmed a non-null
     * node, so {@code advancementNode} is safe to dereference.
     */
    @Unique
    private Component dungeontrain$hintOrPlaceholder() {
        ResourceLocation id = advancementNode.holder().id();
        String key = "advancements." + id.getNamespace() + "."
            + id.getPath().replace('/', '.') + ".hint";
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("advancements.dungeontrain.hidden_description");
    }
}
