package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.TabCompleteAdvancements;
import games.brennan.dungeontrain.advancement.TabGateways;
import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.ClientAdvancementsAccessor;
import net.minecraft.Util;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * "What you still need": click the Everything Burrito, or a tab-complete advancement (Dungeon Train Explored and
 * friends, or its copy inside its tab), and it is <b>pinned</b>. While pinned, each tile it still needs is haloed
 * ({@link games.brennan.dungeontrain.compat.AdvancementTileDecor}) and each tab holding one gets the same aqua
 * halo around it ({@link #currentTabMarked}), on the vanilla screen and on Better Advancements alike. The pin
 * survives switching tabs and closing the screen; clicking it again, or clicking any other advancement, clears it
 * ({@link #onClick}, from {@link AdvancementTrackClick}). Leaving the world clears it too.
 *
 * <p>The rules are the server's — {@link CompletionistAdvancement#isRequiredId} and the tab-complete
 * {@code complete} map — read against the client's own tree, which only ever holds advancements the player
 * can see, so a hidden one is never haloed. The relay's {@code notRequired} list lives on the server only;
 * an advancement it drops still halos here, a rare and harmless difference.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CapstoneNeeds {

    /** Tab-marker results are reused for this long, so a frame doesn't walk the tree once per tab. */
    private static final long TAB_CACHE_MILLIS = 250L;
    /** How far a tab's halo extends past the tab on each side, as the tiles' halo does. */
    public static final int HALO_PAD = 2;
    /** The halo tint: vanilla's aqua, as on the tiles ({@code AdvancementTileDecor}). */
    private static final float HALO_R = 0.33f, HALO_G = 1.0f, HALO_B = 1.0f;

    private static ResourceLocation pinned;
    private static Set<ResourceLocation> tabsCache = Set.of();
    private static ResourceLocation tabsCacheFor;
    private static long tabsCacheAt;
    /** The tab whose icon is being drawn — set around {@code drawIcon} by the tab mixins. */
    private static ResourceLocation drawingTab;

    private CapstoneNeeds() {}

    /** The pinned capstone, or null. */
    public static ResourceLocation pinned() {
        return pinned;
    }

    /**
     * A left click on advancement {@code clicked}. A capstone toggles the pin and the click is used up (returns
     * true); any other advancement clears the pin and keeps its own click behaviour (returns false).
     */
    public static boolean onClick(ResourceLocation clicked) {
        TabGateways.Layout layout = TabGateways.layout();
        boolean capstone = isCapstone(layout, capstoneOf(layout, clicked));
        pinned = nextPin(layout, pinned, clicked);
        return capstone;
    }

    /** The pin after clicking {@code clicked} with {@code current} pinned. Pure; package-private for tests. */
    static ResourceLocation nextPin(TabGateways.Layout layout, ResourceLocation current, ResourceLocation clicked) {
        ResourceLocation capstone = capstoneOf(layout, clicked);
        if (!isCapstone(layout, capstone)) return null;
        return capstone.equals(current) ? null : capstone;
    }

    static boolean isCapstone(TabGateways.Layout layout, ResourceLocation id) {
        return CompletionistAdvancement.ID.equals(id) || layout.complete().containsKey(id.toString());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pinned = null;
        tabsCacheFor = null;
    }

    /** Should {@code node} be haloed: unearned and needed by the pinned capstone? */
    public static boolean highlights(AdvancementNode node, AdvancementProgress progress) {
        if (pinned == null || node == null || (progress != null && progress.isDone())) return false;
        return needs(TabGateways.layout(), pinned, node);
    }

    // ---- tab marker ----------------------------------------------------------

    /** Called by the tab mixins around a tab's {@code drawTab}: which tab is drawing (null after). */
    public static void drawingTab(ResourceLocation tabRoot) {
        drawingTab = tabRoot;
    }

    /** Is the tab being drawn one that holds an unearned advancement the pinned capstone needs? */
    public static boolean currentTabMarked() {
        return drawingTab != null && pinned != null && tabsNeeding().contains(drawingTab);
    }

    /** Draw {@code draw} tinted aqua — the tab halo, matching the tiles' "still needed" halo. */
    public static void inHaloColour(GuiGraphics g, Runnable draw) {
        RenderSystem.enableBlend();
        g.setColor(HALO_R, HALO_G, HALO_B, 1.0f);
        draw.run();
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** The roots of the tabs that hold an unearned advancement the pinned capstone needs, cached briefly. */
    static Set<ResourceLocation> tabsNeeding() {
        long now = Util.getMillis();
        if (pinned.equals(tabsCacheFor) && now - tabsCacheAt < TAB_CACHE_MILLIS) return tabsCache;
        Set<ResourceLocation> out = new HashSet<>();
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            ClientAdvancements advancements = connection.getAdvancements();
            Map<?, AdvancementProgress> progress = ((ClientAdvancementsAccessor) advancements).dungeontrain$progress();
            TabGateways.Layout layout = TabGateways.layout();
            for (AdvancementNode node : advancements.getTree().nodes()) {
                AdvancementProgress p = progress.get(node.holder());
                if ((p == null || !p.isDone()) && needs(layout, pinned, node)) out.add(node.root().holder().id());
            }
        }
        tabsCache = Set.copyOf(out);
        tabsCacheFor = pinned;
        tabsCacheAt = now;
        return tabsCache;
    }

    // ---- rules ----------------------------------------------------------------

    /** A tab-complete advancement's in-tab copy stands for the original. Package-private for tests. */
    static ResourceLocation capstoneOf(TabGateways.Layout layout, ResourceLocation clicked) {
        String original = layout.copies().get(clicked.toString());
        if (original != null && layout.complete().containsKey(original)) return ResourceLocation.tryParse(original);
        return clicked;
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
