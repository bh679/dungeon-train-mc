package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Shared holder for the mod version + git branch baked into the jar at build
 * time via {@code processResources} (see build.gradle). Loaded once on class
 * init and reused by every overlay/screen that needs build info, plus
 * common/server code that needs the dev-vs-release signal (e.g. dev-vs-live
 * Discord relay routing in {@code DungeonTrain.commonSetup}).
 *
 * <p><b>Do not relocate this class.</b> It is pure (a classpath resource read,
 * no client-only references) so it loads on dedicated servers too — and the
 * bundled sibling mod <b>PlayerMob</b> references it by this exact FQN
 * ({@code games.brennan.dungeontrain.client.VersionInfo}) from its
 * {@code DungeonTrainHud} compat. Moving the class is a binary-breaking change
 * for that bundled jar (NoClassDefFoundError at client setup). It lives in
 * {@code client} for historical reasons; common code referencing it is fine
 * because the class itself has no client-only dependencies.</p>
 */
public final class VersionInfo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROPERTIES_PATH = "/dungeontrain_version.properties";
    private static final String UNKNOWN = "?";

    public static final String VERSION;
    public static final String BRANCH;
    public static final String DISPLAY;
    /**
     * Clock hours in which any Dungeon Train repo saw a commit, de-duplicated across repos —
     * the development-hours figure the Contribute page shows. Baked by the {@code devHours}
     * closure in build.gradle; {@code 0} when the build could read no history at all, which
     * callers must treat as "unknown" and show nothing. See
     * {@link games.brennan.dungeontrain.client.support.DevHours}.
     */
    public static final int DEV_HOURS;
    /**
     * Updates shipped in the window {@link #UPDATES_WINDOW_MONTHS} names — MINOR version bumps,
     * one per Gate 3 merge. {@code 0} means the build could count none, which callers must treat
     * as <b>unknown</b> and show nothing. Baked by the {@code updateStats} closure in build.gradle
     * as the offline fallback for the relay-served figure; see
     * {@link games.brennan.dungeontrain.client.support.UpdateStats}.
     */
    public static final int UPDATES_COUNT;
    /**
     * How many months {@link #UPDATES_COUNT} covers — the project's own age rounded up, capped at
     * the twelve the card renders as "1 year". A five-month-old game offers "in 5 months".
     */
    public static final int UPDATES_WINDOW_MONTHS;
    /** Updates shipped in the last 30 days — the card's timeframe when the week is too thin. */
    public static final int UPDATES_MONTH;
    /** Updates shipped in the last 7 days — the card's default timeframe. */
    public static final int UPDATES_WEEK;
    /**
     * The day the newest baked version landed, {@code yyyy-MM-dd}, or {@code ""} when unknown.
     * The offline stand-in for the relay's latest-release timestamp: the closest thing a jar can
     * know about "when was the last update" without asking anyone.
     */
    public static final String LAST_UPDATE_DATE;

    static {
        String version = UNKNOWN;
        String branch = UNKNOWN;
        int devHours = 0;
        int updatesCount = 0;
        int updatesWindowMonths = 0;
        int updatesMonth = 0;
        int updatesWeek = 0;
        String lastUpdateDate = "";
        try (InputStream in = VersionInfo.class.getResourceAsStream(PROPERTIES_PATH)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", UNKNOWN);
                branch = props.getProperty("branch", UNKNOWN);
                devHours = parseHours(props.getProperty("dev_hours"));
                updatesCount = parseCount(props.getProperty("updates_count"), "updates_count");
                updatesWindowMonths = parseCount(props.getProperty("updates_window_months"),
                        "updates_window_months");
                updatesMonth = parseCount(props.getProperty("updates_month"), "updates_month");
                updatesWeek = parseCount(props.getProperty("updates_week"), "updates_week");
                String day = props.getProperty("last_update_date");
                lastUpdateDate = day == null ? "" : day.trim();
            } else {
                LOGGER.warn("VersionInfo: resource {} not found — using fallback", PROPERTIES_PATH);
            }
        } catch (Exception e) {
            LOGGER.warn("VersionInfo: failed to load {} — using fallback", PROPERTIES_PATH, e);
        }
        VERSION = version;
        BRANCH = branch;
        DEV_HOURS = devHours;
        UPDATES_COUNT = updatesCount;
        UPDATES_WINDOW_MONTHS = updatesWindowMonths;
        UPDATES_MONTH = updatesMonth;
        UPDATES_WEEK = updatesWeek;
        LAST_UPDATE_DATE = lastUpdateDate;
        DISPLAY = "Dungeon Train v" + VERSION + " (" + BRANCH + ")";
    }

    /** Absent, blank or non-numeric all mean "unknown" — never a crash in front of a player. */
    private static int parseHours(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("VersionInfo: unparseable dev_hours '{}' — treating as unknown", raw);
            return 0;
        }
    }

    /**
     * As {@link #parseHours}, for the update counters — absent, blank or non-numeric all mean
     * "unknown" ({@code 0}), never a crash in front of a player.
     */
    private static int parseCount(String raw, String key) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("VersionInfo: unparseable {} '{}' — treating as unknown", key, raw);
            return 0;
        }
    }

    private VersionInfo() {}
}
