package games.brennan.dungeontrain.template.dateperiod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bundled festival list, {@code /data/dungeontrain/date_periods.json}, in picker order.
 *
 * <p>Read once from the classpath on first use. It is bundled data only, with no per-install
 * override, because a period id written into a shared template must mean the same days on every
 * install. A malformed entry is logged and skipped, never fatal.</p>
 */
public final class DatePeriods {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String BUNDLED_RESOURCE = "/data/dungeontrain/date_periods.json";

    /** Picker groups, in display order. */
    public static final List<String> MONTH_GROUPS = List.of("jan_apr", "may_aug", "sep_dec");

    private static volatile Map<String, DatePeriod> byId;

    private DatePeriods() {}

    /** Every period, in picker order. */
    public static List<DatePeriod> all() {
        return List.copyOf(load().values());
    }

    public static Optional<DatePeriod> get(String id) {
        return Optional.ofNullable(load().get(id));
    }

    public static boolean exists(String id) {
        return load().containsKey(id);
    }

    /** The ids of every period on {@code day}. */
    public static Set<String> activeOn(LocalDate day) {
        Set<String> out = new LinkedHashSet<>();
        for (DatePeriod p : load().values()) {
            if (p.isOn(day)) out.add(p.id());
        }
        return Collections.unmodifiableSet(out);
    }

    private static Map<String, DatePeriod> load() {
        Map<String, DatePeriod> local = byId;
        if (local != null) return local;
        synchronized (DatePeriods.class) {
            if (byId == null) byId = readBundled();
            return byId;
        }
    }

    private static Map<String, DatePeriod> readBundled() {
        try (InputStream in = DatePeriods.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                LOGGER.error("[DungeonTrain] {} is missing: no date periods, every seasonal template stays off.",
                    BUNDLED_RESOURCE);
                return Map.of();
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return Collections.unmodifiableMap(parse(JsonParser.parseReader(r).getAsJsonObject()));
            }
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Could not read {}: every seasonal template stays off.", BUNDLED_RESOURCE, e);
            return Map.of();
        }
    }

    /** Parses the file's root object. Package-private for tests. */
    static LinkedHashMap<String, DatePeriod> parse(JsonObject root) {
        LinkedHashMap<String, DatePeriod> out = new LinkedHashMap<>();
        for (JsonElement el : root.getAsJsonArray("periods")) {
            try {
                DatePeriod p = parsePeriod(el.getAsJsonObject());
                if (out.putIfAbsent(p.id(), p) != null) {
                    LOGGER.warn("[DungeonTrain] Duplicate date period '{}' in {}: kept the first.", p.id(), BUNDLED_RESOURCE);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[DungeonTrain] Skipped a malformed date period in {}: {}", BUNDLED_RESOURCE, el, e);
            }
        }
        return out;
    }

    private static DatePeriod parsePeriod(JsonObject o) {
        String id = o.get("id").getAsString();
        String months = o.get("months").getAsString();
        if (!MONTH_GROUPS.contains(months)) throw new IllegalArgumentException("unknown months group " + months);
        DatePeriod.Rule rule;
        if (o.has("fixed")) {
            JsonObject f = o.getAsJsonObject("fixed");
            rule = new DatePeriod.Fixed(monthDay(f.get("from").getAsString()), monthDay(f.get("to").getAsString()));
        } else if (o.has("easter")) {
            JsonObject e = o.getAsJsonObject("easter");
            rule = new DatePeriod.EasterRelative(e.get("from").getAsInt(), e.get("to").getAsInt());
        } else if (o.has("table")) {
            JsonObject t = o.getAsJsonObject("table");
            List<LocalDate> days = new ArrayList<>();
            for (JsonElement d : t.getAsJsonArray("days")) days.add(LocalDate.parse(d.getAsString()));
            rule = new DatePeriod.Table(t.get("before").getAsInt(), t.get("after").getAsInt(), days);
        } else {
            throw new IllegalArgumentException(id + " has no fixed, easter or table rule");
        }
        return new DatePeriod(id, months, rule);
    }

    private static MonthDay monthDay(String mmdd) {
        return MonthDay.parse("--" + mmdd);
    }

    /** Table periods with no main day in {@code year}: they are off all that year. */
    public static List<String> missingTableYears(int year) {
        List<String> out = new ArrayList<>();
        for (DatePeriod p : load().values()) {
            if (p.rule() instanceof DatePeriod.Table t && !t.covers(year)) out.add(p.id());
        }
        return out;
    }
}
