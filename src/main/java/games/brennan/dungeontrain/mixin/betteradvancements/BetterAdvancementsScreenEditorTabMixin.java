package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.AdvancementTabGate;
import net.minecraft.advancements.AdvancementNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Better Advancements half of the advancement-tab gate (other mods' tabs, the editor tab) — see {@link AdvancementTabGate}
 * for the rules and
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
    private void dungeontrain$hideTab(AdvancementNode node, CallbackInfo ci) {
        if (AdvancementTabGate.shouldHideTab(node.holder().id())) {
            ci.cancel();
        }
    }
}
