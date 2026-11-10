package games.brennan.dungeontrain.client.logo;

import java.util.Locale;

/**
 * Where the ring logo shows on the main menu. Brennan hasn't picked yet, so a dev-only system
 * property ({@code -Ddungeontrain.logoRing=background|wordmark|off}) switches between the
 * candidates for side-by-side screenshots; the default is {@link Title#BACKGROUND}. Once one is
 * chosen the others and the property go.
 */
public final class LogoRingPlacement {

    /** The main-menu placements under comparison. */
    public enum Title {
        /** A large ring centred behind the menu, over the panorama. */
        BACKGROUND,
        /** A ring centred behind the DUNGEON TRAIN wordmark. */
        WORDMARK,
        OFF
    }

    private LogoRingPlacement() {}

    public static Title title() { return parse(System.getProperty("dungeontrain.logoRing")); }

    /** The loading panel's ring, above "Preparing your journey"; off only with the property set to off. */
    public static boolean onLoadingScreens() { return title() != Title.OFF; }

    static Title parse(String value) {
        if (value == null || value.isBlank()) return Title.BACKGROUND;
        try {
            return Title.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Title.BACKGROUND;
        }
    }
}
