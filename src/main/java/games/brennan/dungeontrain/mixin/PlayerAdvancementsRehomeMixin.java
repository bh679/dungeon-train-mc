package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementReveal;
import games.brennan.dungeontrain.advancement.BandAdvancements;
import games.brennan.dungeontrain.advancement.HiddenGap;
import games.brennan.dungeontrain.net.AdvancementGapsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
 *
 * <p>The walk also counts the hidden advancements it passes. Those counts go to the client in an
 * {@link AdvancementGapsPacket} sent just before this packet, so a link that skips
 * {@link HiddenGap#MIN_HIDDEN_FOR_MARKER} or more is drawn with a "…" across it.</p>
 *
 * <p>A link is only written when its advancement is sent, so the parent each frontier advancement was
 * sent with is remembered per player. When a later flush reveals a skipped parent, the advancements now
 * hanging from the wrong ancestor are listed as re-links in that same {@link AdvancementGapsPacket}, and the
 * client moves them in place instead of leaving the revealed parent as a side branch.</p>
 *
 * <p>The same "added" list is what an earn just revealed: after a flush that follows an earn, the
 * player gets a chat line naming the newly visible advancements they haven't earned yet — see
 * {@link AdvancementReveal}.</p>
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsRehomeMixin {

    @Shadow
    private AdvancementTree tree;

    @Shadow
    @Final
    private Set<AdvancementHolder> visible;

    @Shadow
    public abstract AdvancementProgress getOrStartProgress(AdvancementHolder holder);

    /** An advancement was completed since the last flush, so this flush's reveals are announced. */
    @Unique
    private boolean dungeontrain$earnedSinceFlush;

    /** The reveal line built in {@link #dungeontrain$rehomeChainNodes}; sent after the packet goes out. */
    @Unique
    private Component dungeontrain$pendingReveal;

    @Inject(method = "award", at = @At("RETURN"))
    private void dungeontrain$noteEarn(AdvancementHolder holder, String criterion,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && getOrStartProgress(holder).isDone()) {
            dungeontrain$earnedSinceFlush = true;
        }
    }

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
        if (reset) dungeontrain$sentParent.clear();
        dungeontrain$pendingReveal = (!reset && dungeontrain$earnedSinceFlush) ? dungeontrain$revealLine(added) : null;
        dungeontrain$earnedSinceFlush = false;
        removed.forEach(dungeontrain$sentParent::remove);
        List<AdvancementHolder> rehomed = new ArrayList<>(added.size());
        Map<ResourceLocation, Integer> gaps = new HashMap<>();
        Set<ResourceLocation> addedIds = new HashSet<>();
        for (AdvancementHolder holder : added) {
            addedIds.add(holder.id());
            rehomed.add(dungeontrain$rehome(holder, gaps));
        }
        Map<ResourceLocation, ResourceLocation> relinks = reset ? Map.of() : dungeontrain$relinks(addedIds, gaps);
        dungeontrain$pendingGaps = (reset || !gaps.isEmpty() || !relinks.isEmpty())
            ? new AdvancementGapsPacket(reset, gaps, relinks) : null;
        return new ClientboundUpdateAdvancementsPacket(reset, rehomed, removed, progress);
    }

    /** The parent each frontier advancement was last sent to this player with (absent: none, or a tab head). */
    @Unique
    private final Map<ResourceLocation, ResourceLocation> dungeontrain$sentParent = new HashMap<>();

    /**
     * Advancements already on the client whose nearest visible ancestor changed this flush — a skipped
     * parent was just revealed. Records their new counts in {@code gaps} and their new parents as sent.
     */
    @Unique
    private Map<ResourceLocation, ResourceLocation> dungeontrain$relinks(Set<ResourceLocation> addedIds,
                                                                          Map<ResourceLocation, Integer> gaps) {
        if (dungeontrain$sentParent.isEmpty()) return Map.of();
        List<AdvancementNode> onClient = new ArrayList<>();
        for (AdvancementHolder holder : this.visible) {
            if (addedIds.contains(holder.id()) || !dungeontrain$sentParent.containsKey(holder.id())) continue;
            AdvancementNode node = this.tree.get(holder.id());
            if (node != null) onClient.add(node);
        }
        Map<ResourceLocation, ResourceLocation> relinks = new HashMap<>();
        for (HiddenGap.Relink<ResourceLocation> r : HiddenGap.changedLinks(onClient, n -> n.holder().id(),
                AdvancementNode::parent, n -> this.visible.contains(n.holder()), dungeontrain$sentParent)) {
            relinks.put(r.child(), r.newParent());
            gaps.put(r.child(), r.skipped());
            dungeontrain$sentParent.put(r.child(), r.newParent());
        }
        return relinks;
    }

    /** Built alongside the vanilla packet in {@link #dungeontrain$rehomeChainNodes}; sent just before it. */
    @Unique
    private AdvancementGapsPacket dungeontrain$pendingGaps;

    /**
     * Sends the hidden-advancement counts ahead of the vanilla packet they describe, so the client has
     * them when that packet's update lays the tab out. The packet expression is built (and the redirect
     * above run) before this send is invoked.
     */
    @Inject(method = "flushDirty",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void dungeontrain$sendGaps(ServerPlayer player, CallbackInfo ci) {
        AdvancementGapsPacket pending = dungeontrain$pendingGaps;
        dungeontrain$pendingGaps = null;
        if (pending != null) DungeonTrainNet.sendTo(player, pending);
    }

    /** Sends the reveal line once the packet it describes is on its way, so the names are already in L. */
    @Inject(method = "flushDirty",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                     shift = At.Shift.AFTER))
    private void dungeontrain$sendReveal(ServerPlayer player, CallbackInfo ci) {
        Component reveal = dungeontrain$pendingReveal;
        dungeontrain$pendingReveal = null;
        if (reveal != null) player.sendSystemMessage(reveal);
    }

    /** The chat line naming {@code added}'s unearned advancements, or null when there are none. */
    @Unique
    private Component dungeontrain$revealLine(Collection<AdvancementHolder> added) {
        List<AdvancementHolder> revealed = AdvancementReveal.select(added,
            holder -> getOrStartProgress(holder).isDone(),
            holder -> holder.value().display().map(DisplayInfo::getTitle).map(Component::getString));
        List<Component> names = new ArrayList<>(revealed.size());
        for (AdvancementHolder holder : revealed) holder.value().name().ifPresent(names::add);
        return AdvancementReveal.message(names).orElse(null);
    }

    /**
     * Rewrites {@code holder}'s synced parent to its nearest visible ancestor, if needed, and records how
     * many hidden advancements that skipped into {@code gaps} (0 for a direct link).
     */
    private AdvancementHolder dungeontrain$rehome(AdvancementHolder holder, Map<ResourceLocation, Integer> gaps) {
        ResourceLocation id = holder.id();
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())
                || !BandAdvancements.isFrontierTab(id.getPath())) {
            return holder;
        }

        AdvancementNode node = this.tree.get(id);
        if (node == null || node.parent() == null) {
            return holder;
        }
        HiddenGap.Walk<AdvancementNode> walk =
            HiddenGap.walk(node, AdvancementNode::parent, n -> this.visible.contains(n.holder()));
        gaps.put(id, walk.skipped());
        if (walk.ancestor() == null) {
            dungeontrain$sentParent.remove(id);
        } else {
            dungeontrain$sentParent.put(id, walk.ancestor().holder().id());
        }
        if (walk.ancestor() == node.parent()) {
            return holder;
        }

        AdvancementNode ancestor = walk.ancestor();
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
