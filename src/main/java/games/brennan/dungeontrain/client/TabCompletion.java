package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.TabGateways;
import games.brennan.dungeontrain.mixin.client.ClientAdvancementsAccessor;
import net.minecraft.Util;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * A fully completed tab goes gold on the advancements screens: its tab, and the window border while it is
 * selected (vanilla and Better Advancements alike, through the tab and window mixins).
 *
 * <p>"Complete" is read from the advancement that already means it — the client only knows about advancements
 * it can see, so counting what is visible would light a tab with hidden ones too early. Dungeon Train is complete
 * with the Everything Burrito; every other tab with its tab-complete advancement ({@code advancement_tabs.json}
 * → {@code complete}: Dungeon Train Explored, Challenge Complete, …). Tabs with neither (the Editor tab,
 * vanilla's, other mods') never go gold.</p>
 */
public final class TabCompletion {

    /** Results are reused for this long, so a frame doesn't look the same advancement up per tab. */
    private static final long CACHE_MILLIS = 250L;
    /** The gold tint, multiplied onto the grey tab and window sprites. */
    private static final float GOLD_R = 1.0f, GOLD_G = 0.80f, GOLD_B = 0.28f;

    private static final Map<ResourceLocation, Boolean> cache = new HashMap<>();
    private static long cacheAt;

    private TabCompletion() {}

    /** The advancement whose earning completes {@code tabRoot}, or null when the tab has none. Pure; package-private for tests. */
    static ResourceLocation capstoneFor(TabGateways.Layout layout, ResourceLocation tabRoot) {
        if (CompletionistAdvancement.DUNGEON_TRAIN_ROOT.equals(tabRoot)) return CompletionistAdvancement.ID;
        for (Map.Entry<String, String> e : layout.complete().entrySet()) {
            if (e.getValue().equals(tabRoot.toString())) return ResourceLocation.tryParse(e.getKey());
        }
        return null;
    }

    /** Has this player completed the tab rooted at {@code tabRoot}? */
    public static boolean isComplete(ResourceLocation tabRoot) {
        if (tabRoot == null) return false;
        long now = Util.getMillis();
        if (now - cacheAt > CACHE_MILLIS) {
            cache.clear();
            cacheAt = now;
        }
        return cache.computeIfAbsent(tabRoot, TabCompletion::lookUp);
    }

    private static boolean lookUp(ResourceLocation tabRoot) {
        ResourceLocation capstone = capstoneFor(TabGateways.layout(), tabRoot);
        var connection = Minecraft.getInstance().getConnection();
        if (capstone == null || connection == null) return false;
        AdvancementHolder holder = connection.getAdvancements().get(capstone);
        if (holder == null) return false; // not sent: hidden, so not earned
        AdvancementProgress p = ((ClientAdvancementsAccessor) connection.getAdvancements()).dungeontrain$progress().get(holder);
        return p != null && p.isDone();
    }

    /** Draw {@code draw} tinted gold. */
    public static void inGold(GuiGraphics g, Runnable draw) {
        RenderSystem.enableBlend();
        g.setColor(GOLD_R, GOLD_G, GOLD_B, 1.0f);
        draw.run();
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
