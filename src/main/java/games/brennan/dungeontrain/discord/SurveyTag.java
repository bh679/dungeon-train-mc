package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.relay.LatestReleaseCache;
import net.neoforged.fml.ModList;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The small tag stamped on a feedback answer — the embed footer in the player's thread, and the
 * header line of the copy in the survey-results channel:
 *
 * <pre>🟠 DT 0.1093.0 · Russian · Mods: 87 · Games 12</pre>
 *
 * <p>The dot says how current the build was ({@link VersionFreshness}); the rest says who is
 * answering — the language they play in, how modded their game is, and how many games they have
 * played (every death, Free Play included).</p>
 */
public final class SurveyTag {

    /**
     * What a locale code looks like. The language arrives from the client and lands in a Discord
     * message, so anything that is not a plain code is dropped rather than relayed.
     */
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    /**
     * The game's joke languages, whose codes either borrow a real language's ({@code lol} is ISO's
     * code for Mongo) or sit on a real one as a fake region ({@code en_pt}). Java would name these
     * wrongly, so they are named here; every other code is left to {@link Locale}.
     */
    private static final Map<String, String> MINECRAFT_ONLY_LANGUAGES = Map.of(
            "lol_us", "LOLCAT",
            "en_pt", "Pirate Speak",
            "en_ud", "Upside-down English",
            "enp", "Anglish",
            "enws", "Shakespearean English");

    private SurveyTag() {}

    /** The tag for this player, right now. Server thread. */
    public static String forPlayer(UUID playerId, String clientLanguage) {
        VersionFreshness freshness = VersionFreshness.classify(VersionInfo.VERSION,
                LatestReleaseCache.latestVersion(), VersionInfo.LAST_UPDATE_DATE,
                LocalDate.now(ZoneOffset.UTC));
        return format(freshness, VersionInfo.VERSION, clientLanguage,
                ModList.get().getMods().size(), GlobalPlayerStats.totalGames(playerId));
    }

    /** Pure assembly of the tag, so its shape can be pinned without a running server. */
    static String format(VersionFreshness freshness, String version, String language, int mods, long games) {
        StringBuilder tag = new StringBuilder()
                .append(freshness.dot()).append(" DT ").append(version);
        String languageName = languageName(language);
        if (!languageName.isEmpty()) {
            tag.append(" · ").append(languageName);
        }
        return tag.append(" · Mods: ").append(mods)
                .append(" · Games ").append(games)
                .toString();
    }

    /**
     * The English name of a client locale code — {@code ru_ru} → {@code Russian} — or {@code ""}
     * when the code is missing or is not a plain locale code. The region is left off: the tag is
     * for seeing at a glance what language someone plays in. A code Java has no name for is
     * shown as it came.
     */
    static String languageName(String code) {
        if (code == null || !LANGUAGE_CODE.matcher(code.strip()).matches()) {
            return "";
        }
        String plain = code.strip();
        String jokeName = MINECRAFT_ONLY_LANGUAGES.get(plain.toLowerCase(Locale.ROOT));
        if (jokeName != null) {
            return jokeName;
        }
        Locale locale = Locale.forLanguageTag(plain.replace('_', '-'));
        String name = locale.getDisplayLanguage(Locale.ENGLISH);
        boolean unnamed = name.isBlank() || name.equalsIgnoreCase(locale.getLanguage());
        return unnamed ? plain : name;
    }
}
