package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.TreeNodePosition;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compacts the {@code dungeontrain:*} advancement tabs to the currently
 * <em>visible</em> nodes, so the fog-of-war tree grows dynamically instead of
 * showing widescreen gaps where hidden nodes were laid out.
 *
 * <p>Node positions are computed once server-side by
 * {@code ServerAdvancementManager} via {@link TreeNodePosition#run} over the
 * <em>whole</em> tree, then transmitted in {@code DisplayInfo} (its x/y are part
 * of the stream codec). Because DT hides most of the {@code dungeon_train} tree
 * and only reveals a node once its parent is earned
 * (see {@link games.brennan.dungeontrain.mixin.AdvancementVisibilityEvaluatorMixin}),
 * the client receives only the frontier — but each node keeps its full-tree
 * coordinate. The result is scattered widgets, some off-screen (blank), with
 * reserved gaps for nodes that aren't there.</p>
 *
 * <p>This client-only mixin re-runs vanilla's own {@link TreeNodePosition}
 * algorithm on the client's tree after every advancements packet
 * ({@link ClientAdvancements#update}). The client tree contains only the
 * visible nodes (removed nodes are unlinked from their parents), so the layout
 * comes out tight and re-expands as more nodes are revealed. The mutated
 * {@code DisplayInfo} instances are the client's own deserialized copies —
 * never the shared server objects — so this cannot affect the server-side
 * canonical layout or other players.</p>
 *
 * <p>Scoped to the {@code dungeontrain} namespace, so vanilla and other-mod
 * advancement tabs keep their server-sent positions untouched. Widgets are
 * (re)built from this tree both on screen-open ({@code AdvancementTree.setListener}
 * replays the nodes) and live, so the compacted coordinates are what the
 * {@code AdvancementWidget}s read.</p>
 */
@Mixin(ClientAdvancements.class)
public abstract class AdvancementsCompactLayoutMixin {

    /**
     * Extra horizontal breathing room between depth columns. Vanilla lays
     * columns 1 unit (28px) apart; with the frontier reveal a lone parent sits
     * right against a dense child column, which reads as cramped. Scaling each
     * node's column coordinate widens the gap without touching the (already
     * tight) vertical spacing.
     */
    @Unique
    private static final float DUNGEONTRAIN_COLUMN_SPREAD = 1.5F;

    @Inject(method = "update", at = @At("TAIL"))
    private void dungeontrain$compactVisibleLayout(CallbackInfo ci) {
        ClientAdvancements self = (ClientAdvancements) (Object) this;
        for (AdvancementNode root : self.getTree().roots()) {
            if (!DungeonTrain.MOD_ID.equals(root.holder().id().getNamespace())) continue;
            // TreeNodePosition.run throws on a display-less root; DT roots always
            // have display, but guard anyway for safety.
            if (root.advancement().display().isEmpty()) continue;
            TreeNodePosition.run(root);
            dungeontrain$spreadColumns(root);
        }
    }

    /**
     * Widen the layout horizontally by scaling every node's column coordinate,
     * leaving the row (Y) untouched. Runs after {@link TreeNodePosition#run}
     * on the client's visible-only tree, so it only affects DT tabs.
     */
    @Unique
    private static void dungeontrain$spreadColumns(AdvancementNode node) {
        node.advancement().display().ifPresent(d -> d.setLocation(d.getX() * DUNGEONTRAIN_COLUMN_SPREAD, d.getY()));
        for (AdvancementNode child : node.children()) {
            dungeontrain$spreadColumns(child);
        }
    }
}
