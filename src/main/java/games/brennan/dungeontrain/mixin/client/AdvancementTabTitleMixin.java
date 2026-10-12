package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.AdvancementTabTitles;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names a tab by {@link AdvancementTabTitles} instead of its root's title ("Train Explorer", not
 * "Dungeon Train Explorer"). The title is fixed once the tab is built and is what both the tab-strip
 * tooltip and the window heading read. Better Advancements' twin is
 * {@code games.brennan.dungeontrain.mixin.betteradvancements.BetterAdvancementTabTitleMixin}.
 */
@Mixin(AdvancementTab.class)
public abstract class AdvancementTabTitleMixin {

    @Shadow @Final private AdvancementNode rootNode;

    @Shadow @Final @Mutable private Component title;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dungeontrain$nameTab(CallbackInfo ci) {
        this.title = AdvancementTabTitles.titleFor(this.rootNode.holder().id(), this.title);
    }
}
