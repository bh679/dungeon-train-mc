package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.worldgen.BandOption;
import games.brennan.dungeontrain.worldgen.LapBand;

/**
 * Localised names for the editor's {@link BandOption band options} and their groups —
 * {@code editor_menu.band_option.*} / {@code editor_menu.band_group.*}.
 */
public final class BandLabels {

    private BandLabels() {}

    public static String group(BandOption.Group group) {
        return MenuLang.named("band_group", group.token(), group.displayName());
    }

    /** The option's own name ("Pre Far Lands"). */
    public static String option(BandOption option) {
        return MenuLang.named("band_option", option.token(), option.displayName());
    }

    /** Group-qualified name ("Legacy · Pre Far Lands") — letters repeat across groups. */
    public static String qualified(BandOption option) {
        return group(option.group()) + " · " + option(option);
    }

    /**
     * Compact per-group letters of the options a band mask turns on ({@code "O N E · P F · C"}); a
     * partly-on option gets a trailing {@code ~}. "all" when every band is on, an em dash when none.
     */
    public static String summary(int mask) {
        if ((mask & LapBand.ALL_MASK) == LapBand.ALL_MASK) return MenuLang.t("stages.all");
        StringBuilder sb = new StringBuilder();
        for (BandOption.Group group : BandOption.Group.values()) {
            StringBuilder letters = new StringBuilder();
            for (BandOption o : group.options()) {
                BandOption.State state = o.state(mask);
                if (state == BandOption.State.NONE) continue;
                if (letters.length() > 0) letters.append(' ');
                letters.append(o.letter());
                if (state == BandOption.State.SOME) letters.append('~');
            }
            if (letters.length() == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(letters);
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }
}
