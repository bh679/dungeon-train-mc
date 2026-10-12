package games.brennan.dungeontrain.client.version.compare;

import java.util.Locale;
import java.util.Optional;

/**
 * Where the death screen's Update button goes: DT's own page at brennan.games, which lists the
 * newest build on each launcher with the player's first (dp-relay {@code public/dungeontrain/update/}).
 * Sending every player there rather than to a launcher keeps a CurseForge build from linking straight
 * to Modrinth while still telling them a newer release is out when CurseForge's review lags.
 *
 * <p>The query carries only the installed version and the launcher, which the page uses to order the
 * launchers and list what is new. Pure, so it is unit-tested.</p>
 */
public final class UpdatePage {

    public static final String BASE_URL = "https://brennan.games/dungeontrain/update/";
    /** Shown as the button's destination ("Opens brennan.games, …"). A site name, not translated. */
    public static final String SITE_NAME = "brennan.games";

    private UpdatePage() {}

    /** {@code …/update/?v=0.927.0&from=curseforge}; without a known version just {@code from}. */
    public static String url(Optional<FullSemver> installed, Platform launcher) {
        String from = "from=" + launcher.name().toLowerCase(Locale.ROOT);
        return BASE_URL + "?" + installed.map(v -> "v=" + v + "&" + from).orElse(from);
    }
}
