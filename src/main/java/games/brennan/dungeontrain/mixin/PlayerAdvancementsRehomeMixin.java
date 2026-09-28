package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Companion to {@link AdvancementVisibilityEvaluatorMixin}: keeps the client's advancement tree
 * connected when that mixin hides an interior chain node on the {@code dungeon_train} or
 * {@code secrete_menu} tab.
 *
 * <p>{@code AdvancementVisibilityEvaluator} only decides per-node visibility — the tree SHAPE the
 * client draws comes from each synced {@link Advancement}'s own {@code parent} field, which is
 * fixed at datapack load ({@code BandAdvancementChainRewriter}) and identical for every player,
 * regardless of progress. If an earned node's real parent is one of the newly-hidden interior
 * nodes, the client never receives that parent id at all, so
 * {@code AdvancementTree.tryInsert} can never resolve it — and the whole batch insert gives up,
 * silently dropping the earned node (and everything chained after it) from the client's tree.
 * That would mean an earned advancement stops showing at all, which is worse than the bug this
 * is fixing.</p>
 *
 * <p>This mixin rewrites the {@code parent} field of the {@link AdvancementHolder}s placed in the
 * outgoing {@link ClientboundUpdateAdvancementsPacket}'s "added" list — never the canonical
 * server-side advancement, only this one packet's copy — so a node whose real parent isn't
 * currently visible instead points at the nearest ancestor that IS visible, walking up the real
 * chain. The player sees their earned advancement connected to whatever they can actually see,
 * exactly as if the hidden ones in between were never there.</p>
 *
 * <p>Hooks {@code PlayerAdvancements.flushDirty} right before it builds the packet: by that point
 * {@code this.visible} already reflects this pass's settled state (every
 * {@code updateTreeVisibility} call for the flush has already run), so the walk-up reads
 * consistent data regardless of the post-order traversal {@code AdvancementVisibilityEvaluator}
 * itself uses internally.</p>
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsRehomeMixin {

    @Shadow
    private AdvancementTree tree;

    @Shadow
    @Final
    private Set<AdvancementHolder> visible;

    @Redirect(
        method = "flushDirty",
        at = @At(value = "NEW",
                 target = "(ZLjava/util/Collection;Ljava/util/Set;Ljava/util/Map;)Lnet/minecraft/network/protocol/game/ClientboundUpdateAdvancementsPacket;")
    )
    private ClientboundUpdateAdvancementsPacket dungeontrain$rehomeChainNodes(
        boolean reset,
        Collection<AdvancementHolder> added,
        Set<ResourceLocation> removed,
        Map<ResourceLocation, AdvancementProgress> progress
    ) {
        List<AdvancementHolder> rehomed = new ArrayList<>(added.size());
        for (AdvancementHolder holder : added) {
            rehomed.add(dungeontrain$rehome(holder));
        }
        return new ClientboundUpdateAdvancementsPacket(reset, rehomed, removed, progress);
    }

    /** Rewrites {@code holder}'s synced parent to its nearest visible ancestor, if needed. */
    private AdvancementHolder dungeontrain$rehome(AdvancementHolder holder) {
        ResourceLocation id = holder.id();
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())
                || !BandAdvancements.isFrontierTab(id.getPath())) {
            return holder;
        }

        AdvancementNode node = this.tree.get(id);
        AdvancementNode parent = node == null ? null : node.parent();
        if (parent == null || this.visible.contains(parent.holder())) {
            return holder;
        }

        AdvancementNode ancestor = parent.parent();
        while (ancestor != null && !this.visible.contains(ancestor.holder())) {
            ancestor = ancestor.parent();
        }

        Advancement original = holder.value();
        Advancement rehomedAdvancement = new Advancement(
            ancestor == null ? Optional.empty() : Optional.of(ancestor.holder().id()),
            original.display(),
            original.rewards(),
            original.criteria(),
            original.requirements(),
            original.sendsTelemetryEvent(),
            original.name()
        );
        return new AdvancementHolder(id, rehomedAdvancement);
    }
}
