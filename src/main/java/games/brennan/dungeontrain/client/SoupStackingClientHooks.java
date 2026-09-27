package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client side of {@link games.brennan.dungeontrain.cheat.FarmersDelightSoupStacking}. Only ever
 * reached behind a {@code FMLEnvironment.dist.isClient()} check, so it is never class-loaded on a
 * dedicated server.
 */
public final class SoupStackingClientHooks {

    private SoupStackingClientHooks() {}

    /**
     * Is a mod's config menu (Configured, NeoForge's config screen, …) what the player is looking at?
     * Read as "a non-vanilla screen is open": a hand edit to the file is made with the game on the
     * title screen, the pause menu or in play, all of which are vanilla screens (or none).
     */
    public static boolean isConfigMenuOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        Screen screen = mc.screen;
        return screen != null && !screen.getClass().getName().startsWith("net.minecraft.");
    }

    /** Open the "turn on stackable soups?" question over whatever menu is open. Any thread. */
    public static void askToEnable() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new SoupStackingConfirmScreen(mc.screen)));
    }
}
