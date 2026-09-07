package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;

/**
 * Hands the input that drives the hotbar back to the player while a menu screen is open.
 *
 * <p>A {@code Screen} swallows the wheel and the number keys. The editor menus depend on both
 * still working — the {@code Blocks: + held} rows take whichever block the author is holding —
 * so every editor screen routes them here rather than each carrying its own copy. Slot changes
 * need no packet: {@code MultiPlayerGameMode.tick} sends the carried-item packet whenever
 * {@code Inventory.selected} drifts, and it ticks regardless of any open screen.</p>
 */
public final class HotbarPassthrough {

    private HotbarPassthrough() {}

    /** Scroll the hotbar the way vanilla's own mouse handler does; false with no player. */
    public static boolean scroll(Minecraft mc, double scrollY) {
        if (mc == null || mc.player == null || scrollY == 0) return false;
        mc.player.getInventory().swapPaint(scrollY);
        return true;
    }

    /** 1-9 select a hotbar slot, exactly as they would with no screen open. */
    public static boolean key(Minecraft mc, int keyCode, int scanCode) {
        if (mc == null || mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.options.keyHotbarSlots[i].matches(keyCode, scanCode)) {
                mc.player.getInventory().selected = i;
                return true;
            }
        }
        return false;
    }
}
