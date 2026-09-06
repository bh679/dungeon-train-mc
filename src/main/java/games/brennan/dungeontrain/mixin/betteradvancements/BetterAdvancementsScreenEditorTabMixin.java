package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.EditorAdvancementsGate;
import net.minecraft.advancements.AdvancementNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Better Advancements half of the editor-tab gate — see {@link EditorAdvancementsGate}
 * for the rule and
 * {@code games.brennan.dungeontrain.mixin.client.AdvancementsScreenEditorTabMixin} for the
 * vanilla screen. BA replaces the advancements screen outright and ships enabled in the
 * modpack, so without this the tab would still show for most players.
 *
 * <p>{@code BetterAdvancementsScreen} implements the same {@code ClientAdvancements.Listener}
 * callback, and its {@code getAdvancementWidget} is null-safe for a root with no tab, so
 * cancelling here is as safe as on the vanilla screen. Cancelling before the tab is built
 * also keeps BA's tab paging ({@code maxPages}) counting only the tabs it actually shows.</p>
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementsScreen", remap = false)
public abstract class BetterAdvancementsScreenEditorTabMixin {

    @Inject(method = "onAddAdvancementRoot", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$hideEditorTab(AdvancementNode node, CallbackInfo ci) {
        if (EditorAdvancementsGate.isEditorAdvancement(node.holder().id())
            && EditorAdvancementsGate.shouldHideEditorTab()) {
            ci.cancel();
        }
    }
}
