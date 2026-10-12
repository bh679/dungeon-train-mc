package games.brennan.dungeontrain.mixin.betteradvancements;

import games.brennan.dungeontrain.client.TabCompletion;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Better Advancements twin of {@code AdvancementsScreenGoldWindowMixin}. BA 0.4.3 draws its window frame (corners
 * and repeated sides) at the top of {@code renderWindow}, then its tabs once {@code this.tabs.size() > 1}: the
 * gold colour is set at the head and put back at that first size check, so only the frame is tinted. The
 * selected tab is BA's own type, so its root is read through its public {@code getRootNode()}.
 */
@Mixin(targets = "betteradvancements.common.gui.BetterAdvancementsScreen", remap = false)
public abstract class BetterAdvancementsScreenGoldWindowMixin {

    @Unique
    private boolean dungeontrain$gold;

    @Inject(method = "renderWindow", at = @At("HEAD"))
    private void dungeontrain$goldFrame(GuiGraphics g, int left, int top, int right, int bottom, int maxTabs, int skip,
                                        CallbackInfo ci) {
        dungeontrain$gold = TabCompletion.isComplete(dungeontrain$selectedRoot());
        if (dungeontrain$gold) g.setColor(1.0f, 0.80f, 0.28f, 1.0f);
    }

    @Inject(method = "renderWindow", at = @At(value = "INVOKE", target = "Ljava/util/Map;size()I", ordinal = 0))
    private void dungeontrain$endGoldFrame(GuiGraphics g, int left, int top, int right, int bottom, int maxTabs, int skip,
                                           CallbackInfo ci) {
        if (dungeontrain$gold) g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    @Unique
    private net.minecraft.resources.ResourceLocation dungeontrain$selectedRoot() {
        try {
            java.lang.reflect.Field f = this.getClass().getDeclaredField("selectedTab");
            f.setAccessible(true);
            Object tab = f.get(this);
            if (tab == null) return null;
            Method m = tab.getClass().getMethod("getRootNode");
            return ((net.minecraft.advancements.AdvancementNode) m.invoke(tab)).holder().id();
        } catch (ReflectiveOperationException | ClassCastException ex) {
            return null;
        }
    }
}
