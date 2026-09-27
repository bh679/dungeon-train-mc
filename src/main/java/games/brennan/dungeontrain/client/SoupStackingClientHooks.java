package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;

/**
 * Client side of {@link games.brennan.dungeontrain.cheat.FarmersDelightSoupStacking}. Only ever
 * reached behind a {@code FMLEnvironment.dist.isClient()} check, so it is never class-loaded on a
 * dedicated server.
 */
public final class SoupStackingClientHooks {

    private SoupStackingClientHooks() {}

    /**
     * Is this the client (render) thread? A config reload posted here came from an in-game save —
     * Configured and NeoForge's config screen both save on it. NeoForge's file watcher, which is the
     * only way a hand edit to the file reaches the game, posts from its own thread instead.
     */
    public static boolean isClientThread() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.isSameThread();
    }

    /** Open the "turn on stackable soups?" question over whatever menu is open. Any thread. */
    public static void askToEnable() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new SoupStackingConfirmScreen(mc.screen)));
    }
}
