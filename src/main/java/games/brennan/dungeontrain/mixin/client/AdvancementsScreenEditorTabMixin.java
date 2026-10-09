package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.AdvancementTabGate;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps other mods' tabs, and the {@code dungeontrain:editor/*} tab when the player can't earn any
 * of it, out of vanilla's advancements screen — see {@link AdvancementTabGate} for the rules.
 *
 * <p>Cancelled at the root-added callback rather than by removing the tab afterwards, so the
 * tab is never constructed and never claims a slot in the tab strip — no gap where it would
 * have been. Vanilla's later lookups for that root
 * ({@code getAdvancementWidget} → {@code tabs.get(...)}) are null-safe, so a progress packet
 * for an editor advancement arriving while the screen is open is simply a no-op.</p>
 *
 * <p>Better Advancements replaces this screen entirely and is bundled with the modpack; its
 * equivalent lives in
 * {@code games.brennan.dungeontrain.mixin.betteradvancements.BetterAdvancementsScreenEditorTabMixin}.</p>
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsScreenEditorTabMixin {

    @Inject(method = "onAddAdvancementRoot", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$hideTab(AdvancementNode node, CallbackInfo ci) {
        if (AdvancementTabGate.shouldHideTab(node.holder().id())) {
            ci.cancel();
        }
    }
}
