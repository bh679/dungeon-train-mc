package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.HiddenGap;
import games.brennan.dungeontrain.mixin.client.AdvancementNodeAccessor;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Client copy of how many hidden advancements sit between each synced advancement and the one it is drawn
 * from, fed by {@link games.brennan.dungeontrain.net.AdvancementGapsPacket}. The compact layout gives a
 * marked link an empty column and the connector draws a "…" across it — see {@link HiddenGap}.
 *
 * <p>Also holds the packet's re-links: advancements to move under a newly revealed parent. They wait here
 * until the vanilla packet that adds that parent has been applied, then {@link #applyRelinks} moves each
 * one in place (its progress untouched, so no toast replays).</p>
 *
 * <p>Client thread only. Cleared on logout; the server's first advancements packet after login is a
 * reset, which clears it again.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementGapsClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Better Advancements' replacement screen, matched by name (no compile dependency on BA). */
    private static final String BETTER_ADVANCEMENTS_SCREEN = "betteradvancements.common.gui.BetterAdvancementsScreen";

    private static Map<ResourceLocation, Integer> gaps = Map.of();

    private static Map<ResourceLocation, ResourceLocation> pendingRelinks = Map.of();

    private AdvancementGapsClient() {}

    /** Whether the link into {@code id} gets a "…". */
    public static boolean isMarked(ResourceLocation id) {
        Integer skipped = id == null ? null : gaps.get(id);
        return skipped != null && HiddenGap.isMarked(skipped);
    }

    public static void apply(boolean reset, Map<ResourceLocation, Integer> update,
                             Map<ResourceLocation, ResourceLocation> relinks) {
        Map<ResourceLocation, Integer> next = reset ? new HashMap<>() : new HashMap<>(gaps);
        update.forEach((id, skipped) -> {
            if (skipped > 0) next.put(id, skipped);
            else next.remove(id);
        });
        gaps = Map.copyOf(next);
        Map<ResourceLocation, ResourceLocation> pending = reset ? new HashMap<>() : new HashMap<>(pendingRelinks);
        pending.putAll(relinks);
        pendingRelinks = Map.copyOf(pending);
    }

    /**
     * Moves every pending re-link's advancement under its new parent in the client's tree. Runs after the
     * vanilla advancements packet is applied, so a just-revealed parent is already there; an advancement or
     * parent the client doesn't hold is skipped.
     *
     * @return whether anything moved
     */
    public static boolean applyRelinks(AdvancementTree tree) {
        if (pendingRelinks.isEmpty()) return false;
        Map<ResourceLocation, ResourceLocation> relinks = pendingRelinks;
        pendingRelinks = Map.of();
        boolean moved = false;
        for (Map.Entry<ResourceLocation, ResourceLocation> e : relinks.entrySet()) {
            AdvancementNode child = tree.get(e.getKey());
            AdvancementNode parent = tree.get(e.getValue());
            // A tab head is also held in the tree's root set; never move one.
            if (child == null || parent == null || child.parent() == null) {
                LOGGER.debug("[DungeonTrain] Advancement re-link {} -> {} skipped: not on the client", e.getKey(), e.getValue());
                continue;
            }
            if (HiddenGap.moveUnder(child, parent, NODE_LINKS)) {
                LOGGER.debug("[DungeonTrain] Advancement {} moved under revealed {}", e.getKey(), e.getValue());
                moved = true;
            }
        }
        return moved;
    }

    /** Rebuilds an open advancements screen so its widgets pick up moved links (vanilla's init rebuilds its tabs). */
    public static void refreshOpenScreen() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) return;
        if (screen instanceof AdvancementsScreen || BETTER_ADVANCEMENTS_SCREEN.equals(screen.getClass().getName())) {
            screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        }
    }

    /** Drops counts for advancements the client no longer holds (removed from the tree). */
    public static void retainOnly(Predicate<ResourceLocation> stillPresent) {
        if (gaps.isEmpty()) return;
        Map<ResourceLocation, Integer> next = new HashMap<>(gaps);
        if (next.keySet().removeIf(id -> !stillPresent.test(id))) gaps = Map.copyOf(next);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        gaps = Map.of();
        pendingRelinks = Map.of();
    }

    private static final HiddenGap.Links<AdvancementNode> NODE_LINKS = new HiddenGap.Links<>() {
        @Override
        public AdvancementNode parentOf(AdvancementNode node) {
            return node.parent();
        }

        @Override
        public void setParent(AdvancementNode node, AdvancementNode parent) {
            ((AdvancementNodeAccessor) node).dungeontrain$setParent(parent);
        }

        @Override
        public void removeChild(AdvancementNode parent, AdvancementNode child) {
            ((AdvancementNodeAccessor) parent).dungeontrain$children().remove(child);
        }

        @Override
        public void addChild(AdvancementNode parent, AdvancementNode child) {
            parent.addChild(child);
        }
    };
}
