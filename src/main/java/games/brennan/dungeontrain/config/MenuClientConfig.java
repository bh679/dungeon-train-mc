package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The main menu's look ({@code config/dungeontrain-menu-client.toml}). Its own file, like the
 * Contribute and Live Feed knobs, so the display config record stays untouched; nothing here has
 * any fair-play meaning.
 */
public final class MenuClientConfig {

    /** What the main menu shows behind its buttons. */
    public enum Backdrop {
        /** The animated logo over the Live Feed when someone is streaming, else tributed photos. */
        LIVE_AND_PHOTOS,
        /** The animated logo over tributed photos only: never the live stream. */
        PHOTOS_ONLY,
        /** The main menu as it was: Minecraft's own panorama, no logo or photos. */
        NONE
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<Backdrop> BACKDROP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("menu");
        BACKDROP = b
            .comment("What the main menu shows behind its buttons. LIVE_AND_PHOTOS: the animated logo over the Live Feed when someone is streaming, else tributed photos. PHOTOS_ONLY: the logo over tributed photos, never the live stream. NONE: the main menu as it was, Minecraft's own panorama.")
            .defineEnum("backdrop", Backdrop.LIVE_AND_PHOTOS);
        b.pop();
        SPEC = b.build();
    }

    private MenuClientConfig() {}

    /** The chosen backdrop; the default before the config has loaded. */
    public static Backdrop backdrop() { return SPEC.isLoaded() ? BACKDROP.get() : Backdrop.LIVE_AND_PHOTOS; }

    public static void setBackdrop(Backdrop backdrop) {
        if (!SPEC.isLoaded()) return;
        BACKDROP.set(backdrop);
        BACKDROP.save();
    }
}
