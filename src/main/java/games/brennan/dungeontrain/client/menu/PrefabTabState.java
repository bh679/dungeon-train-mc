package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.item.ContentsPrefabItem;
import games.brennan.dungeontrain.item.VariantPrefabItem;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client singleton — the state of the left-side prefab tabs in the creative
 * inventory.
 *
 * <p>Lifecycle: populated by {@link games.brennan.dungeontrain.net.PrefabRegistrySyncPacket}
 * on login, cleared on logout (see
 * {@link games.brennan.dungeontrain.client.menu.PrefabClientLifecycleEvents}).
 * Active-tab + scroll state is per-session and resets to {@link Tab#NONE}
 * any time the inventory closes.</p>
 *
 * <p>Side-tab UI is mod-private — there is no server-side equivalent. All
 * state mutation happens on the client thread; reads from the mixin during
 * render are also on the client thread.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabTabState {

    public enum Tab {
        NONE,
        VARIANTS,
        LOOT
    }

    private static List<String> variantIds = Collections.emptyList();
    private static List<String> contentsIds = Collections.emptyList();
    private static Tab activeTab = Tab.NONE;
    private static int scrollOffset = 0;

    private PrefabTabState() {}

    /** Replace the cached registry — called from the sync packet handler. */
    public static void applyRegistry(List<String> variantIds, List<String> contentsIds) {
        PrefabTabState.variantIds = List.copyOf(variantIds);
        PrefabTabState.contentsIds = List.copyOf(contentsIds);
    }

    /** Clear all state — called when the player disconnects from the server. */
    public static void clear() {
        variantIds = Collections.emptyList();
        contentsIds = Collections.emptyList();
        activeTab = Tab.NONE;
        scrollOffset = 0;
    }

    public static Tab activeTab() {
        return activeTab;
    }

    /**
     * Toggle a side tab. Clicking the active tab deactivates; clicking a
     * different tab switches to it (and resets scroll). Idempotent.
     */
    public static void toggle(Tab tab) {
        if (tab == Tab.NONE) {
            activeTab = Tab.NONE;
            scrollOffset = 0;
            return;
        }
        if (activeTab == tab) {
            activeTab = Tab.NONE;
        } else {
            activeTab = tab;
        }
        scrollOffset = 0;
    }

    /** Force the active tab to NONE — used when a vanilla creative tab is selected. */
    public static void deactivate() {
        activeTab = Tab.NONE;
        scrollOffset = 0;
    }

    public static int scrollOffset() {
        return scrollOffset;
    }

    /** Adjust scroll offset by the given delta, clamped to {@code [0, max]}. */
    public static void scrollBy(int delta, int max) {
        int next = scrollOffset + delta;
        if (next < 0) next = 0;
        if (next > max) next = max;
        scrollOffset = next;
    }

    /** Ids for the currently active tab; empty list when no tab is active. */
    public static List<String> activeIds() {
        return switch (activeTab) {
            case VARIANTS -> variantIds;
            case LOOT -> contentsIds;
            case NONE -> Collections.emptyList();
        };
    }

    public static List<String> variantIds() {
        return variantIds;
    }

    public static List<String> contentsIds() {
        return contentsIds;
    }

    /**
     * Build the ItemStack to put in the player's cursor when an entry is
     * clicked. The kind is derived from the active tab; the id from the
     * grid index. Returns {@link ItemStack#EMPTY} if the tab is NONE or the
     * index is out of bounds.
     */
    public static ItemStack stackFor(Tab tab, int index) {
        return switch (tab) {
            case VARIANTS -> {
                if (index < 0 || index >= variantIds.size()) yield ItemStack.EMPTY;
                yield VariantPrefabItem.stackForPrefab(
                    ModItems.VARIANT_PREFAB.get(), variantIds.get(index));
            }
            case LOOT -> {
                if (index < 0 || index >= contentsIds.size()) yield ItemStack.EMPTY;
                yield ContentsPrefabItem.stackForPrefab(
                    ModItems.CONTENTS_PREFAB.get(), contentsIds.get(index));
            }
            case NONE -> ItemStack.EMPTY;
        };
    }

    /** Convenience for tests / debug — total entry count across both tabs. */
    public static int totalEntries() {
        return variantIds.size() + contentsIds.size();
    }

    /** Pre-seed builtins for offline (no-server) creative testing. Visible for the side tab init. */
    public static void seedBuiltinsIfEmpty() {
        if (variantIds.isEmpty()) {
            variantIds = new ArrayList<>(List.of("standard", "flatbed", "windowed", "solid_roof"));
        }
        if (contentsIds.isEmpty()) {
            contentsIds = new ArrayList<>(List.of("default"));
        }
    }
}
