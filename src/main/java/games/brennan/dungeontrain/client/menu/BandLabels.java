package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.worldgen.LapBand;

/** Localised names for {@link LapBand} bands and laps — {@code editor_menu.band.*} / {@code editor_menu.lap.*}. */
public final class BandLabels {

    private BandLabels() {}

    public static String lap(LapBand.Lap lap) {
        return MenuLang.named("lap", lap.token(), lap.displayName());
    }

    /** The band's own name ("BetterNether"). */
    public static String band(LapBand band) {
        return MenuLang.named("band", band.token(), band.displayName());
    }

    /** Lap-qualified name ("Mod · BetterNether") — letters and names repeat across laps. */
    public static String qualified(LapBand band) {
        return lap(band.lap()) + " · " + band(band);
    }

    /**
     * Compact per-lap letters of a mask's bands ({@code "V:NE M:N"}); "all" when every band is on, an
     * em dash when none.
     */
    public static String summary(int mask) {
        if ((mask & LapBand.ALL_MASK) == LapBand.ALL_MASK) return MenuLang.t("stages.all");
        StringBuilder sb = new StringBuilder();
        for (LapBand.Lap lap : LapBand.Lap.values()) {
            StringBuilder letters = new StringBuilder();
            for (LapBand b : lap.members()) {
                if ((mask & b.bit()) != 0) letters.append(b.letter());
            }
            if (letters.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(lap.letter()).append(':').append(letters);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }
}
