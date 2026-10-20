package games.brennan.dungeontrain.client.support.currency;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * What one AUD buys in each {@link DisplayCurrency}: the relay's {@code fxRates}, which ride the
 * donation summary every Contribute screen already fetches ({@code DonationSummaryClient} hands the
 * raw body to {@link #remember}). The relay refreshes them daily from ECB reference rates.
 *
 * <p>The last good table is kept in {@code config/dungeontrain/fx-rates.json}, so a player offline
 * (or before the first fetch of a launch) still sees their currency. With no table at all, nothing
 * converts and every amount shows in AUD, as before this existed. Rates are never invented here.</p>
 */
public final class CurrencyRates {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "fx-rates.json";

    private static volatile Map<DisplayCurrency, Double> rates;

    private CurrencyRates() {}

    /** The rate for {@code c} (AUD is always 1), or 0 when there is none to convert at. */
    public static double rate(DisplayCurrency c) {
        if (c == DisplayCurrency.AUD) return 1.0;
        Double r = table().get(c);
        return r == null ? 0.0 : r;
    }

    /** Whether {@code c} can be shown: AUD always, anything else only with a rate. */
    public static boolean usable(DisplayCurrency c) {
        return rate(c) > 0;
    }

    /** Take {@code fxRates} from a donation-summary body; keeps the old table when it has none. */
    public static void remember(String body) {
        Map<DisplayCurrency, Double> parsed = parse(body);
        if (parsed.isEmpty()) return;
        rates = parsed;
        save(body);
    }

    private static Map<DisplayCurrency, Double> table() {
        Map<DisplayCurrency, Double> t = rates;
        if (t == null) {
            t = load();
            rates = t;
        }
        return t;
    }

    /**
     * Pure: a summary body (or the cached file, which has the same {@code fxRates} shape) → the rates
     * this jar offers. Unknown codes and non-positive or non-numeric rates are dropped.
     */
    static Map<DisplayCurrency, Double> parse(String body) {
        Map<DisplayCurrency, Double> out = new EnumMap<>(DisplayCurrency.class);
        if (body == null || body.isBlank()) return out;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return out;
            JsonElement fx = root.getAsJsonObject().get("fxRates");
            if (fx == null || !fx.isJsonObject()) return out;
            for (Map.Entry<String, JsonElement> e : fx.getAsJsonObject().entrySet()) {
                DisplayCurrency c = DisplayCurrency.byCode(e.getKey());
                if (c == null || c == DisplayCurrency.AUD || !e.getValue().isJsonPrimitive()
                        || !e.getValue().getAsJsonPrimitive().isNumber()) continue;
                double r = e.getValue().getAsDouble();
                if (Double.isFinite(r) && r > 0) out.put(c, r);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] fxRates parse failed: {}", e.toString());
        }
        return out;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("dungeontrain").resolve(FILE);
    }

    private static Map<DisplayCurrency, Double> load() {
        try {
            Path f = file();
            if (Files.isRegularFile(f)) return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] fx-rates.json unreadable: {}", e.toString());
        }
        return Collections.emptyMap();
    }

    /** Keep only {@code fxRates} (+ its date), never the rest of the summary (names, ledger). */
    private static void save(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject out = new JsonObject();
            out.add("fxRates", root.get("fxRates"));
            if (root.has("fxRatesAsOf")) out.add("fxRatesAsOf", root.get("fxRatesAsOf"));
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[DungeonTrain] fx-rates.json not written: {}", e.toString());
        }
    }
}
