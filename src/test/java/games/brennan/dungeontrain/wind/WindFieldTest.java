package games.brennan.dungeontrain.wind;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wind field: calm before the Upside-down end, rising after it, deterministic, gusting at roughly the
 * configured rate — and agreeing with the tuner's JavaScript port through {@code scripts/wind/golden.json}.
 *
 * <p>Regenerate the golden vectors after an intentional change to {@link WindField} (and its JS twin) with
 * {@code UPDATE_WIND_GOLDEN=1 ./gradlew test --tests '*WindFieldTest*'}.</p>
 */
final class WindFieldTest {

    private static final int SEED = 0x5EEDBEEF;

    private static WindProfile shipped() throws IOException {
        return WindProfile.parse(Files.readString(
                RepoPaths.resources().resolve("data/dungeontrain/wind/profile.json"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("no wind before the Upside-down end, nor at its very edge")
    void calmBeforeStart() throws IOException {
        WindProfile p = shipped();
        for (long t = 0; t < 2000; t += 7) {
            assertTrue(WindField.sample(p, -1.0, t, SEED).isCalm());
            assertTrue(WindField.sample(p, -50000.0, t, SEED).isCalm());
            assertTrue(WindField.sample(p, 0.0, t, SEED).isCalm(), "stage 0 is still");
        }
    }

    @Test
    @DisplayName("the steady push only grows with distance")
    void baseRisesMonotonically() throws IOException {
        WindProfile p = shipped();
        double last = -1;
        for (double d = 0; d <= 120000; d += 250) {
            double base = p.stageAt(d).base();
            assertTrue(base >= last, "base dipped at d=" + d);
            last = base;
        }
        assertTrue(p.stageAt(1500).base() > 0, "very slight wind arrives early");
        assertTrue(p.stageAt(1500).base() < p.stageAt(70000).base() / 4, "...but stays slight");
    }

    @Test
    @DisplayName("same inputs, same wind")
    void deterministic() throws IOException {
        WindProfile p = shipped();
        for (long t = 0; t < 5000; t += 13) {
            assertEquals(WindField.sample(p, 40000, t, SEED), WindField.sample(p, 40000, t, SEED));
        }
        assertFalse(WindField.sample(p, 40000, 1234, SEED).equals(WindField.sample(p, 40000, 1234, SEED + 1)),
                "different seeds give different weather");
    }

    @Test
    @DisplayName("gusts arrive at about the configured rate")
    void gustRate() {
        WindProfile.Stage s = new WindProfile.Stage(0, 0.0, 0.0, 6.0, 0.05, 20, 0, 0, 60);
        WindProfile p = new WindProfile(true, 90, 1, 20, WindProfile.Multipliers.UNIT, java.util.List.of(s));
        int onsets = 0;
        boolean was = false;
        long ticks = 20L * 60 * 200; // 200 minutes
        for (long t = 0; t < ticks; t++) {
            boolean now = !WindField.sample(p, 10, t, SEED).isCalm();
            if (now && !was) onsets++;
            was = now;
        }
        double perMin = onsets / 200.0;
        assertTrue(perMin > 4.5 && perMin < 7.0, "gusts/min = " + perMin);
    }

    @Test
    @DisplayName("hash and noise stay in range")
    void ranges() {
        for (int i = -1000; i < 1000; i++) {
            double h = WindField.hash01(SEED, i, 3);
            assertTrue(h >= 0 && h < 1);
            double n = WindField.noise(SEED, i * 0.37);
            assertTrue(n >= -1 && n <= 1);
        }
    }

    @Test
    @DisplayName("profile JSON round-trips")
    void roundTrip() throws IOException {
        WindProfile p = shipped();
        assertEquals(p, WindProfile.parse(p.toJson()));
    }

    /** Golden vectors shared with scripts/wind/web/wind.js — the tuner shows a parity badge from them. */
    @Test
    @DisplayName("matches the tuner's golden vectors")
    void golden() throws IOException {
        WindProfile p = shipped();
        Path file = RepoPaths.root().resolve("scripts/wind/golden.json");
        double[] ds = {0, 900, 3000, 7777, 12000, 25000, 48000, 70000, 150000};
        long[] ts = {0, 1, 19, 20, 333, 4096, 77777, 1234567};

        if ("1".equals(System.getenv("UPDATE_WIND_GOLDEN"))) {
            JsonObject out = new JsonObject();
            out.addProperty("seed", SEED);
            out.add("profile", JsonParser.parseString(p.toJson()));
            JsonArray rows = new JsonArray();
            for (double d : ds) {
                for (long t : ts) {
                    WindSample w = WindField.sample(p, d, t, SEED);
                    JsonObject r = new JsonObject();
                    r.addProperty("d", d);
                    r.addProperty("tick", t);
                    r.addProperty("strength", w.strength());
                    r.addProperty("headingRad", w.headingRad());
                    r.addProperty("gust01", w.gust01());
                    rows.add(r);
                }
            }
            out.add("rows", rows);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out) + "\n");
        }

        JsonObject golden = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        WindProfile gp = WindProfile.parse(golden.get("profile").toString());
        int seed = golden.get("seed").getAsInt();
        int n = 0;
        for (JsonElement e : golden.getAsJsonArray("rows")) {
            JsonObject r = e.getAsJsonObject();
            WindSample w = WindField.sample(gp, r.get("d").getAsDouble(), r.get("tick").getAsLong(), seed);
            String at = "d=" + r.get("d") + " tick=" + r.get("tick");
            assertEquals(r.get("strength").getAsDouble(), w.strength(), 1e-12, at);
            assertEquals(r.get("headingRad").getAsDouble(), w.headingRad(), 1e-12, at);
            assertEquals(r.get("gust01").getAsDouble(), w.gust01(), 1e-12, at);
            n++;
        }
        assertTrue(n > 0, "golden file has rows");
    }
}
