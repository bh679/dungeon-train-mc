package games.brennan.dungeontrain.client.wind;

import net.neoforged.fml.ModList;

/**
 * Whether Windy's streaks can carry the train wind's direction: Windy is loaded <b>and</b> it is the
 * Configurable fork with the Living Wind hook ({@code xyz.bonfiremc.windy.LivingWindCompat}) DT steers. The
 * same test {@code WindyMixinPlugin} makes at mixin time, repeated here on the game classloader rather than
 * reading the plugin's static, which may live in a different classloader.
 */
final class WindyPresence {

    private static final String HOOK = "xyz.bonfiremc.windy.LivingWindCompat";
    private static volatile Boolean directional;

    private WindyPresence() {}

    static boolean directional() {
        Boolean d = directional;
        if (d == null) {
            d = ModList.get().isLoaded("windy") && hookPresent();
            directional = d;
        }
        return d;
    }

    private static boolean hookPresent() {
        try {
            Class.forName(HOOK, false, WindyPresence.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
