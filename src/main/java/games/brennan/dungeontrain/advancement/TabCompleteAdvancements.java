package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.requirement.AdvancementRequirementOverrides;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Each tab's "tab complete" advancement — Dungeon Train Explored, All Others, Challenge Complete, The
 * Hero's Handbook, Fully Developed, All Out Of Secrets — earned by having every advancement in that tab.
 * Each sits in the Dungeon Train tab under its tab's entry, with a single {@code impossible} criterion
 * this class awards (like the Everything Burrito), and has a gateway copy inside its own tab
 * ({@link TabGateways}) so the tab shows it too.
 *
 * <p>The pairs come from {@code advancement_tabs.json} → {@code complete} (advancement → tab root). A
 * tab's members are read from the live advancement tree on every check — the root and every descendant
 * — so the band journey's load-time re-parenting and any move made in the advancement editor are
 * followed without an id list here. Skipped: the tab-complete copies themselves (a copy would otherwise
 * need itself), band advancements the world's layout never visits ({@link BandAdvancements#reachable}),
 * which no player there can earn, and any the relay operator marked not required, the same escape hatch
 * the Everything Burrito honours.</p>
 */
public final class TabCompleteAdvancements {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The Train Explorer tab's one, kept by id for the tests and the old log line. */
    public static final ResourceLocation TRAIN_EXPLORED =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/train_explored");

    private TabCompleteAdvancements() {}

    /** Is {@code id} a tab-complete advancement (the original, not its in-tab copy)? */
    public static boolean isTabComplete(ResourceLocation id) {
        return TabGateways.layout().complete().containsKey(id.toString());
    }

    /**
     * The advancements a tab needs: {@code root} and everything below it, minus those {@code skip}
     * rejects. Pure over the tree; package-private for tests.
     */
    static List<AdvancementNode> members(AdvancementNode root, Predicate<ResourceLocation> skip) {
        List<AdvancementNode> out = new ArrayList<>();
        collect(root, skip, out);
        return out;
    }

    private static void collect(AdvancementNode node, Predicate<ResourceLocation> skip, List<AdvancementNode> out) {
        if (!skip.test(node.holder().id())) out.add(node);
        for (AdvancementNode child : node.children()) collect(child, skip, out);
    }

    /**
     * The copies of tab-complete advancements: never members of any tab, or a tab's own copy would need
     * itself. Pure over the layout; also read by the client's "what you still need" halo.
     */
    public static Set<String> completeCopies(TabGateways.Layout layout) {
        Set<String> out = new HashSet<>();
        layout.copies().forEach((copy, original) -> { if (layout.complete().containsKey(original)) out.add(copy); });
        return out;
    }

    /** Grant {@code player} every tab-complete advancement whose tab is now done. Idempotent. */
    public static void checkAndGrant(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        TabGateways.Layout layout = TabGateways.layout();
        if (layout.complete().isEmpty()) return;
        Predicate<ResourceLocation> skip = skipRule(completeCopies(layout));
        for (Map.Entry<String, String> e : layout.complete().entrySet()) {
            checkOne(player, server.getAdvancements(), e.getKey(), e.getValue(), skip);
        }
    }

    private static void checkOne(ServerPlayer player, ServerAdvancementManager mgr, String selfId, String tabRootId,
                                 Predicate<ResourceLocation> skip) {
        ResourceLocation selfRl = ResourceLocation.tryParse(selfId), rootRl = ResourceLocation.tryParse(tabRootId);
        if (selfRl == null || rootRl == null) return;
        AdvancementHolder self = mgr.get(selfRl);
        AdvancementNode root = mgr.tree().get(rootRl);
        if (self == null || root == null) return; // data not loaded
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return;

        List<AdvancementNode> needed = members(root, skip);
        for (AdvancementNode node : needed) {
            if (!player.getAdvancements().getOrStartProgress(node.holder()).isDone()) return;
        }
        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted tab complete '{}' ({} advancements under {}) to {}",
                selfId, needed.size(), tabRootId, player.getName().getString());
        }
    }

    /** What not to require in this world: tab-complete copies, unreachable bands and the relay's not-required list. */
    private static Predicate<ResourceLocation> skipRule(Set<String> completeCopies) {
        Set<ResourceLocation> notRequired = AdvancementRequirementOverrides.notRequired();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        Set<String> reachable = cycle.hasLayout() ? BandAdvancements.reachable(cycle.layout()) : null;
        return id -> completeCopies.contains(id.toString()) || notRequired.contains(id)
            || (reachable != null && isUnreachableBand(id, reachable));
    }

    /** A band-journey advancement whose band this world's layout never visits. Package-private for tests. */
    static boolean isUnreachableBand(ResourceLocation id, Set<String> reachable) {
        String prefix = BandAdvancementChainRewriter.PATH_PREFIX;
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace()) || !id.getPath().startsWith(prefix)) return false;
        String name = id.getPath().substring(prefix.length());
        return BandAdvancements.chain(null).contains(name) && !reachable.contains(name);
    }
}
