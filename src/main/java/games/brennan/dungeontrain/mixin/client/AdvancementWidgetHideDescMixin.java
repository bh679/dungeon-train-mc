package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
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
 * the mod's namespace AND is not yet earned, the wrapper substitutes a
 * placeholder list (single line of {@code ???}, pre-wrapped to the
 * widget's render width) for the real description. Earned advancements
 * and non-mod advancements get the unmodified description.</p>
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
     * Lazily-cached placeholder description, computed on first hide so we
     * don't re-split a Component every frame. Width-bound to {@link #width}
     * (same as the real description) so layout calculations stay
     * consistent.
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

    @Unique
    private boolean dungeontrain$shouldHideDescription() {
        if (advancementNode == null) return false;
        ResourceLocation id = advancementNode.holder().id();
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())) return false;
        if (id.getPath().endsWith("/root")) return false;
        // Earned → reveal.
        return progress == null || !progress.isDone();
    }

    @Unique
    private List<FormattedCharSequence> dungeontrain$getHiddenDesc() {
        if (dungeontrain$hiddenDesc == null) {
            Component hidden = Component.translatable("advancements.dungeontrain.hidden_description");
            dungeontrain$hiddenDesc = minecraft.font.split(hidden, width);
        }
        return dungeontrain$hiddenDesc;
    }
}
