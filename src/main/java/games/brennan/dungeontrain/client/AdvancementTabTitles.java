package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.TabGateways;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Tab names that differ from the tab's first advancement. A tab is named after its root's title, but
 * "Train Explorer" is headed by a copy of "Dungeon Train Explorer" and "Others" by a copy of
 * "Others?" — see {@link TabGateways}. The names come from {@code /dungeontrain/advancement_tabs.json}
 * ({@code tabNames}: root id → lang key) and are applied by the tab-title mixins on vanilla's
 * {@code AdvancementTab} and Better Advancements' {@code BetterAdvancementTab}.
 */
public final class AdvancementTabTitles {

    private AdvancementTabTitles() {}

    /** The tab name for the tab rooted at {@code rootId}, or {@code fallback} (the root's own title). */
    public static Component titleFor(ResourceLocation rootId, Component fallback) {
        String key = TabGateways.layout().tabNames().get(rootId.toString());
        return key == null ? fallback : Component.translatable(key);
    }
}
