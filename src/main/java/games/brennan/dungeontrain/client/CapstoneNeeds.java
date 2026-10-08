package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.TabCompleteAdvancements;
import games.brennan.dungeontrain.advancement.TabGateways;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * "What you still need": while the mouse is over the Everything Burrito, or a tab-complete advancement
 * (Dungeon Train Explored and friends, or its copy inside its tab), each tile that capstone still needs is
 * haloed ({@link games.brennan.dungeontrain.compat.AdvancementTileDecor}). Both advancements screens record
 * the hovered tile in {@link HoveredAdvancement}, so this works on the vanilla screen and on Better
 * Advancements alike.
 *
 * <p>The rules are the server's — {@link CompletionistAdvancement#isRequiredId} and the tab-complete
 * {@code complete} map — read against the client's own tree, which only ever holds advancements the player
 * can see, so a hidden one is never haloed. The relay's {@code notRequired} list lives on the server only;
 * an advancement it drops still halos here, a rare and harmless difference.</p>
 */
public final class CapstoneNeeds {

    private CapstoneNeeds() {}

    /** Should {@code node} be haloed this frame: unearned and needed by the hovered capstone? */
    public static boolean highlights(AdvancementNode node, AdvancementProgress progress) {
        if (node == null || (progress != null && progress.isDone())) return false;
        ResourceLocation hovered = HoveredAdvancement.current();
        return hovered != null && needs(TabGateways.layout(), capstoneOf(TabGateways.layout(), hovered), node);
    }

    /** A tab-complete advancement's in-tab copy stands for the original. Package-private for tests. */
    static ResourceLocation capstoneOf(TabGateways.Layout layout, ResourceLocation hovered) {
        String original = layout.copies().get(hovered.toString());
        if (original != null && layout.complete().containsKey(original)) return ResourceLocation.tryParse(original);
        return hovered;
    }

    /** Does {@code capstone} need {@code node}? False for anything that is not a capstone. */
    static boolean needs(TabGateways.Layout layout, ResourceLocation capstone, AdvancementNode node) {
        if (capstone == null) return false;
        ResourceLocation id = node.holder().id();
        if (id.equals(capstone)) return false;
        if (CompletionistAdvancement.ID.equals(capstone)) {
            return node.holder().value().display().isPresent()
                && CompletionistAdvancement.isRequiredId(id, Set.of(), CompletionistAdvancement.inDungeonTrainTab(node));
        }
        String tabRoot = layout.complete().get(capstone.toString());
        if (tabRoot == null) return false;
        return tabRoot.equals(node.root().holder().id().toString())
            && !TabCompleteAdvancements.completeCopies(layout).contains(id.toString());
    }
}
