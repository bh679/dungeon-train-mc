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
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "Dungeon Train Explored" — the Train Explorer tab's capstone, earned by having every advancement in
 * that tab. It sits in the Dungeon Train tab under The Enchiridion's gateway and has a single
 * {@code impossible} criterion this class awards, like the Everything Burrito.
 *
 * <p>The tab's members are read from the live advancement tree on every check — every descendant of
 * {@link #TAB_ROOT} — so the band journey's load-time re-parenting and any later move made in the
 * advancement editor are followed without an id list here. Two kinds are skipped: band advancements the
 * world's layout never visits ({@link BandAdvancements#reachable}), which no player there can earn, and
 * any the relay operator marked not required, the same escape hatch the Everything Burrito honours.</p>
 */
public final class TrainExploredAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/train_explored");

    /** The Train Explorer tab's root: the copy of Dungeon Train Explorer that heads it. */
    public static final ResourceLocation TAB_ROOT =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/tab_train_explorer");

    private TrainExploredAdvancement() {}

    /**
     * The advancements the capstone needs: {@code root} and everything below it, minus those {@code skip}
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

    /** Grant the capstone to {@code player} when every Train Explorer advancement is done. Idempotent. */
    public static void checkAndGrant(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder self = mgr.get(ID);
        AdvancementNode root = mgr.tree().get(TAB_ROOT);
        if (self == null || root == null) return; // data not loaded
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return;

        List<AdvancementNode> needed = members(root, skipRule());
        for (AdvancementNode node : needed) {
            if (!player.getAdvancements().getOrStartProgress(node.holder()).isDone()) return;
        }
        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted 'Dungeon Train Explored' ({} Train Explorer advancements) to {}",
                needed.size(), player.getName().getString());
        }
    }

    /** What not to require in this world: unreachable bands and the relay's not-required list. */
    private static Predicate<ResourceLocation> skipRule() {
        Set<ResourceLocation> notRequired = AdvancementRequirementOverrides.notRequired();
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        Set<String> reachable = cycle.hasLayout() ? BandAdvancements.reachable(cycle.layout()) : null;
        return id -> notRequired.contains(id) || (reachable != null && isUnreachableBand(id, reachable));
    }

    /** A band-journey advancement whose band this world's layout never visits. Package-private for tests. */
    static boolean isUnreachableBand(ResourceLocation id, Set<String> reachable) {
        String prefix = BandAdvancementChainRewriter.PATH_PREFIX;
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace()) || !id.getPath().startsWith(prefix)) return false;
        String name = id.getPath().substring(prefix.length());
        return BandAdvancements.chain(null).contains(name) && !reachable.contains(name);
    }
}
