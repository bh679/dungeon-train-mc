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
import java.util.function.Predicate;

/**
 * Progressive "frontier" reveal for {@code dungeontrain:*} advancements, plus
 * a lift of the vanilla {@code VISIBILITY_DEPTH = 2} cap.
 *
 * <p>The DT {@code dungeon_train} tab marks (almost) every advancement
 * {@code hidden:true} so the tree isn't a wall of spoilers. On its own,
 * vanilla only reveals a {@code hidden:true} advancement once it is
 * <em>itself</em> earned — so the tab would show nothing but a lone root. We
 * want a fog-of-war instead: an advancement stays hidden until its
 * <em>direct parent</em> is earned, at which point it (and its siblings under
 * that parent) unhide as the next visible step.</p>
 *
 * <p>Vanilla {@link AdvancementVisibilityEvaluator#evaluateVisibility}
 * decides whether to sync an unearned advancement to the client via
 * {@code evaluateVisiblityForUnfinishedNode(stack)}. This mixin wraps that
 * result (MixinExtras {@link ModifyExpressionValue}). When the original says
 * "hide" and the node is a {@code dungeontrain} advancement whose direct
 * parent has been completed, we force it visible — revealing the frontier one
 * ring beyond what's earned. Because the evaluator re-runs whenever progress
 * changes, earning a node reveals its children on the next sync.</p>
 *
 * <p>Grandchildren stay hidden (their parent isn't done yet), so the reveal
 * never runs ahead of the player. The root ({@code hidden:false}) is always
 * visible so the tab renders. Earned nodes and ancestors of earned nodes are
 * already handled by vanilla ({@code isSelfOrDescendantDone}).</p>
 *
 * <p>For the rare non-hidden DT advancement, the legacy behaviour is kept:
 * scan the whole ancestor rule stack for a {@code SHOW} so the depth cap
 * doesn't drop a deep non-hidden node below an earned ancestor.
 * {@code VisibilityRule} is package-private, so the comparison goes via
 * {@code String.valueOf(...)} against the enum name.</p>
 *
 * <p>Vanilla and other-mod advancements are untouched — the namespace check
 * returns early.</p>
 */
@Mixin(AdvancementVisibilityEvaluator.class)
public abstract class AdvancementVisibilityEvaluatorMixin {

    @ModifyExpressionValue(
        method = "evaluateVisibility(Lnet/minecraft/advancements/AdvancementNode;Lit/unimi/dsi/fastutil/Stack;Ljava/util/function/Predicate;Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;)Z",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator;evaluateVisiblityForUnfinishedNode(Lit/unimi/dsi/fastutil/Stack;)Z")
    )
    private static boolean dungeontrain$revealFrontierForModNamespace(
        boolean original,
        @Local(argsOnly = true) AdvancementNode node,
        @Local(argsOnly = true) Stack<?> stack,
        @Local(argsOnly = true) Predicate<AdvancementNode> isDoneTest
    ) {
        if (original) return true;
        ResourceLocation id = node.holder().id();
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())) return false;

        // Frontier reveal: unhide an advancement once its DIRECT parent is
        // earned. Applies to hidden and non-hidden nodes alike; the root has
        // no parent and is already visible via its SHOW rule.
        AdvancementNode parent = node.parent();
        if (parent != null && isDoneTest.test(parent)) return true;

        // Legacy depth-cap lift for non-hidden DT advancements only: reveal a
        // deep non-hidden node when any ancestor rule is SHOW (an earned
        // ancestor), regardless of the vanilla 2-hop window. Hidden nodes fall
        // through to the frontier rule above and otherwise stay hidden.
        Optional<DisplayInfo> display = node.advancement().display();
        if (display.isEmpty() || display.get().isHidden()) return false;
        java.util.List<?> entries = (java.util.List<?>) stack;
        for (int i = 0; i < entries.size(); i++) {
            if ("SHOW".equals(String.valueOf(entries.get(i)))) return true;
        }
        return false;
    }
}
