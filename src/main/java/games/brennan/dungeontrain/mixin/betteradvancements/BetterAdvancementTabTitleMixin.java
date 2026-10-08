package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.AdvancementTabTitles;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Better Advancements half of the tab-name override — see {@link AdvancementTabTitles} and
 * {@code games.brennan.dungeontrain.mixin.client.AdvancementTabTitleMixin}. BA's
 * {@code BetterAdvancementTab} keeps the same {@code rootNode} / {@code title} pair as vanilla's tab
 * (checked against BetterAdvancements-NeoForge-1.21.1-0.4.3.21) and is targeted by name so this
 * compiles without BA on the classpath.
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementTab", remap = false)
public abstract class BetterAdvancementTabTitleMixin {

    @Shadow @Final private AdvancementNode rootNode;

    @Shadow @Final @Mutable private Component title;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dungeontrain$nameTab(CallbackInfo ci) {
        this.title = AdvancementTabTitles.titleFor(this.rootNode.holder().id(), this.title);
    }
}
