package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementVisibilityRule;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.TabGateways;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.advancements.AdvancementVisibilityEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

/**
 * Strict "frontier-only" reveal for the {@code dungeontrain:dungeon_train/*}
 * band chain: a player sees earned advancements and the single unearned step
 * immediately after their furthest earned one — nothing else. Anything in
 * between (a newly inserted advancement that retroactively became a parent
 * of one already earned, or a node skipped over by a buggy/out-of-order
 * grant) stays hidden, as if it doesn't exist, even once some later node in
 * the chain is done.
 *
 * <p>The {@code dungeon_train} tab marks (almost) every advancement
 * {@code hidden:true} so the tree isn't a wall of spoilers, and is a
 * hand-maintained linear chain ({@code BandAdvancements.chain(...)},
 * reparented by {@code BandAdvancementChainRewriter}) — so "ancestor" and
 * "everything before" are effectively the same thing here.</p>
 *
 * <p>Vanilla {@link AdvancementVisibilityEvaluator#evaluateVisibility}
 * computes, per node, {@code flag2 = flag1 || evaluateVisiblityForUnfinishedNode(stack)}
 * where {@code flag1} is "this node or any descendant is done" — vanilla's
 * own ancestor-reveal, which unconditionally shows every node on the path
 * from an earned node back to the root. A narrower wrap around just the
 * {@code evaluateVisiblityForUnfinishedNode} sub-call can't suppress that,
 * since {@code flag1} short-circuits it. So instead this mixin intercepts the
 * final {@code Output.accept(node, flag2)} call and substitutes its own rule
 * for every {@code dungeon_train} chain node, completely replacing vanilla's
 * {@code flag2} rather than adjusting one term of it:</p>
 *
 * <ul>
 *   <li>Node is done (earned) → always visible.</li>
 *   <li>Node isn't done but its <em>direct</em> parent is done → visible
 *       (the frontier, one ring past what's earned).</li>
 *   <li>Otherwise → hidden, regardless of whether some descendant further
 *       along the chain happens to be done.</li>
 * </ul>
 *
 * <p>The root has no parent and is auto-granted (tick trigger), so it's
 * always "done" and falls into the first case; {@code original} is used as a
 * defensive fallback only if that ever isn't true. The separate
 * {@code dungeontrain:editor/*} tab, vanilla advancements, and other mods are
 * untouched — the path check returns {@code original} early.</p>
 *
 * <p>The Secrete Menu tab ({@code dungeontrain:secrete_menu/*}) follows the
 * same rule. Its root has no parent and is not auto-granted, so until it is
 * earned it falls back to {@code original} — hidden — and the tab stays
 * out of sight until the player unlocks it.</p>
 *
 * <p>Each advancement can override that rule in the advancement editor — hidden until parent (the
 * default above), always visible while its parent is, or hidden until earned — see
 * {@link games.brennan.dungeontrain.advancement.AdvancementVisibilityRule}.</p>
 *
 * <p>This only decides whether a node is <em>sent</em> to the client at all —
 * it says nothing about where it's drawn once there. An earned node whose
 * real parent got hidden by this rule needs its synced tree connection
 * rewritten too, or the client drops it as an orphan; see
 * {@link PlayerAdvancementsRehomeMixin}.</p>
 */
@Mixin(AdvancementVisibilityEvaluator.class)
public abstract class AdvancementVisibilityEvaluatorMixin {

    @ModifyArg(
        method = "evaluateVisibility(Lnet/minecraft/advancements/AdvancementNode;Lit/unimi/dsi/fastutil/Stack;Ljava/util/function/Predicate;Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;)Z",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/advancements/AdvancementVisibilityEvaluator$Output;accept(Lnet/minecraft/advancements/AdvancementNode;Z)V")
    )
    private static boolean dungeontrain$frontierOnlyVisibility(
        boolean original,
        @Local(argsOnly = true) AdvancementNode node,
        @Local(argsOnly = true) Predicate<AdvancementNode> isDoneTest
    ) {
        ResourceLocation id = node.holder().id();
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())) return original;
        if (!BandAdvancements.isFrontierTab(id.getPath())) return original;
        // Per-advancement mode from the advancement editor; with none set, the frontier rule
        // (hidden until parent) for a child and vanilla's own answer for a tab head.
        return AdvancementVisibilityRule.isVisible(node, AdvancementNode::parent, isDoneTest,
            n -> AdvancementVisibilityRule.Mode.parse(TabGateways.layout().visibility().get(n.holder().id().toString())),
            n -> original);
    }
}
