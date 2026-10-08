package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.TabGateways;
import games.brennan.dungeontrain.advancement.TabOrder;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps the advancement tree's tab roots in the fixed tab order ({@link TabOrder}) rather than the order the
 * server happened to send them. Both advancements screens (vanilla and Better Advancements) build their tab
 * strip by walking these roots when they open, so this one sort orders both.
 */
@Mixin(AdvancementTree.class)
public abstract class AdvancementTreeOrderMixin {

    @Shadow @Final private Set<AdvancementNode> roots;

    @Inject(method = "tryInsert", at = @At("RETURN"))
    private void dungeontrain$keepTabOrder(AdvancementHolder advancement, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || advancement.value().parent().isPresent() || roots.size() < 2) return;
        List<AdvancementNode> ordered = TabOrder.sorted(TabGateways.layout().order(), new ArrayList<>(roots),
            node -> node.holder().id());
        roots.clear();
        roots.addAll(ordered);
    }
}
