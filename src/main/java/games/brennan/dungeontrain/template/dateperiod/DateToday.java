package games.brennan.dungeontrain.template.dateperiod;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Which date periods are on for this server session.
 *
 * <p>Resolved <b>once</b>, when the server starts, from the server's own local date. A world that
 * runs past midnight into Christmas keeps the rooms it planned: a seasonal pick that flipped
 * mid-session would re-plan portal twins and drop rooms players had already changed. The next start
 * picks the new date up.</p>
 *
 * <p>A dev override ({@code /dungeontrain debug date}) replaces the date and re-resolves at once,
 * so a seasonal room can be tried without changing the machine's clock.</p>
 *
 * <p>Before the first resolve (unit tests, the title screen) no period is on, so seasonal templates
 * stay out.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DateToday {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile LocalDate today;
    private static volatile LocalDate override;
    private static volatile Set<String> active = Set.of();

    private DateToday() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        resolve(LocalDate.now());
    }

    /** The date this session gates on, or {@code null} before the first resolve. */
    public static LocalDate today() {
        return today;
    }

    /** True while a dev override replaces the real date. */
    public static boolean isOverridden() {
        return override != null;
    }

    /** The ids of every period on for this session. Never {@code null}. */
    public static Set<String> activePeriods() {
        return active;
    }

    /** Dev override: gate on {@code date} instead of the real date, from now on. {@code null} clears it. */
    public static void setOverride(LocalDate date) {
        override = date;
        resolve(LocalDate.now());
    }

    /** Pin the active set directly. Tests only. */
    public static void setForTesting(LocalDate date) {
        override = null;
        applyDate(date);
    }

    private static void resolve(LocalDate realToday) {
        LocalDate day = override != null ? override : realToday;
        applyDate(day);
        List<String> missing = DatePeriods.missingTableYears(day.getYear());
        if (!missing.isEmpty()) {
            LOGGER.warn("[DungeonTrain] date_periods.json has no {} dates for {}: those periods stay off this year. "
                + "Add the year's dates to the table.", day.getYear(), missing);
        }
        LOGGER.info("[DungeonTrain] Date periods for {}{}: {}", day, override != null ? " (dev override)" : "",
            active.isEmpty() ? "none" : active);
    }

    private static void applyDate(LocalDate day) {
        today = day;
        active = day == null ? Set.of() : DatePeriods.activeOn(day);
    }
}
