package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.DungeonTrain;
import it.unimi.dsi.fastutil.Stack;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Lifts the vanilla {@code VISIBILITY_DEPTH = 2} cap for
 * {@code dungeontrain:*} advancements so the whole tab subtree is sent to
 * the client once any ancestor on the chain has been earned.
 *
 * <p>Vanilla {@link AdvancementVisibilityEvaluator#evaluateVisibility}
 * decides whether to sync an unearned, non-hidden advancement by calling
 * {@code evaluateVisiblityForUnfinishedNode(stack)}, which only peeks the
 * top three entries of the rule stack. That hides any advancement more
 * than two hops below the nearest earned ancestor — e.g.
 * {@code root → carts_100 → carts_1000 → carts_10000} drops
 * {@code carts_10000} when only {@code root} is earned.</p>
 *
 * <p>This mixin wraps that call via MixinExtras
 * {@link ModifyExpressionValue}. When the original returns {@code false}
 * and the node is in the {@code dungeontrain} namespace and is not marked
 * {@code hidden:true}, it scans the entire rule stack for any
 * {@code SHOW}. If found, the advancement becomes visible regardless of
 * depth.</p>
 *
 * <p>{@code VisibilityRule} is a package-private enum inside the target
 * class, so we cannot import it; comparison goes via
 * {@code String.valueOf(...)} against the enum name (the enum does not
 * override {@code toString}, so {@code name()} and {@code toString()}
 * agree).</p>
 *
 * <p>Vanilla and other-mod advancements are untouched — the namespace
 * check returns early. {@code hidden:true} DT advancements (none exist
 * today, but future-proofing) also stay hidden, so spoiler-style content
 * still works.</p>
 */
@Mixin(AdvancementVisibilityEvaluator.class)
public abstract class AdvancementVisibilityEvaluatorMixin {

    @ModifyExpressionValue(
        method = "evaluateVisibility(Lnet/minecraft/advancements/AdvancementNode;Lit/unimi/dsi/fastutil/Stack;Ljava/util/function/Predicate;Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;)Z",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator;evaluateVisiblityForUnfinishedNode(Lit/unimi/dsi/fastutil/Stack;)Z")
    )
    private static boolean dungeontrain$extendDepthForModNamespace(
        boolean original,
        @Local(argsOnly = true) AdvancementNode node,
        @Local(argsOnly = true) Stack<?> stack
    ) {
        if (original) return true;
        ResourceLocation id = node.holder().id();
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())) return false;
        Optional<DisplayInfo> display = node.advancement().display();
        if (display.isEmpty() || display.get().isHidden()) return false;
        // Vanilla constructs the stack as ObjectArrayList, which implements
        // java.util.List. The fastutil Stack interface itself only exposes
        // push/pop/peek, so we cast to scan ancestors at arbitrary depth.
        java.util.List<?> entries = (java.util.List<?>) stack;
        for (int i = 0; i < entries.size(); i++) {
            if ("SHOW".equals(String.valueOf(entries.get(i)))) return true;
        }
        return false;
    }
}
