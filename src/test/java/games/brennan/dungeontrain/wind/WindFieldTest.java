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
    @DisplayName("strength keeps rising with no upper limit")
    void strengthUnbounded() throws IOException {
        WindProfile p = shipped();
        double[] ds = {1e3, 1e4, 1e5, 1e6, 1e7, 1e8};
        for (int i = 1; i < ds.length; i++) {
            assertTrue(p.stageAt(ds[i]).base() > p.stageAt(ds[i - 1]).base(), "steady at " + ds[i]);
            assertTrue(p.stageAt(ds[i]).gustStrength() > p.stageAt(ds[i - 1]).gustStrength(), "gust at " + ds[i]);
        }
        // Logarithmic: each 10x of distance adds the same amount.
        double step1 = p.stageAt(1e6).base() - p.stageAt(1e5).base();
        double step2 = p.stageAt(1e7).base() - p.stageAt(1e6).base();
        assertEquals(step1, step2, step1 * 0.02);
    }

    @Test
    @DisplayName("gust frequency and length close in on their caps forever without reaching them")
    void gustCapsApproached() throws IOException {
        WindProfile p = shipped();
        double lastRate = -1;
        double lastLen = -1;
        for (double d = 0; d <= 4e6; d += 50000) {
            WindProfile.Stage s = p.stageAt(d);
            assertTrue(s.gustsPerMin() >= lastRate, "rate never drops at " + d);
            assertTrue(s.gustTicks() >= lastLen, "length never drops at " + d);
            // Never past the cap (far out the approach rounds to the cap itself in double precision).
            assertTrue(s.gustsPerMin() <= 60.0, "at most 60/min at " + d);
            assertTrue(s.gustTicks() <= 5.0 * 20, "at most 5 s at " + d);
            lastRate = s.gustsPerMin();
            lastLen = s.gustTicks();
        }
        assertTrue(p.stageAt(1e5).gustsPerMin() < 60.0 && p.stageAt(1e5).gustTicks() < 100.0, "still short of the caps");
        assertTrue(p.stageAt(4e6).gustsPerMin() > 59.0, "near the cap far out");
        assertTrue(p.stageAt(4e6).gustTicks() > 4.99 * 20, "near the cap far out");
    }

    @Test
    @DisplayName("each gust lasts between 0.1 s and the longest allowed there")
    void gustLengthsWithinRange() {
        WindProfile p = new WindProfile(true, 90, 1, 1, 20, WindProfile.Multipliers.UNIT, WindProfile.Weight.NONE, new WindProfile.Curves(
                WindCurve.constant(0), WindCurve.constant(0), WindCurve.constant(1), WindCurve.constant(0.05),
                WindCurve.constant(3.0), WindCurve.constant(0), WindCurve.constant(0), WindCurve.constant(60)));
        int run = 0;
        int shortest = Integer.MAX_VALUE;
        int longest = 0;
        for (long t = 0; t < 20L * 60 * 300; t++) {
            if (!WindField.sample(p, 10, t, SEED).isCalm()) {
                run++;
            } else if (run > 0) {
                shortest = Math.min(shortest, run);
                longest = Math.max(longest, run);
                run = 0;
            }
        }
        assertTrue(shortest <= 6, "some gusts are short: " + shortest);
        // Two gusts landing back to back read as one run, so allow up to two maxima.
        assertTrue(longest <= 2 * 3 * 20, "none past the max beyond a merge: " + longest);
        assertTrue(longest >= 40, "some gusts run long: " + longest);
    }

    @Test
    @DisplayName("lighter things are pushed harder and start moving at lower wind")
    void lighterFirst() throws IOException {
        WindProfile.Weight w = shipped().weight();
        double item = 0.25 * 0.25 * 0.25;
        double arrow = 0.5 * 0.5 * 0.5;
        double chicken = 0.4 * 0.4 * 0.7;
        double player = 0.6 * 0.6 * 1.8;
        double golem = 1.4 * 1.4 * 2.7;
        double[] byWeight = {item, chicken, arrow, player, golem};
        for (int i = 1; i < byWeight.length; i++) {
            assertTrue(w.boost(byWeight[i - 1]) >= w.boost(byWeight[i]), "boost falls with mass at " + i);
            assertTrue(w.threshold(byWeight[i - 1]) < w.threshold(byWeight[i]), "threshold rises with mass at " + i);
        }
        assertTrue(w.boost(item) <= w.maxBoost(), "boost is capped");
        // The order the wind picks them up in: the distance the steady wind first beats each one's grip.
        WindProfile p = shipped();
        double last = -1;
        for (double m : byWeight) {
            double need = w.threshold(m);
            double lo = 0;
            double hi = 1;
            while (p.stageAt(hi).base() <= need && hi < 1e12) hi *= 2;
            for (int i = 0; i < 80; i++) {
                double mid = (lo + hi) / 2;
                if (p.stageAt(mid).base() > need) hi = mid; else lo = mid;
            }
            double d = hi;
            assertTrue(d > last, "heavier starts later (mass " + m + " at " + d + ")");
            last = d;
        }
    }

    @Test
    @DisplayName("sky islands: fewer, weaker gusts and longer calm gaps in the steady wind")
    void skyStyle() throws IOException {
        WindProfile p = shipped();
        WindField.Style sky = new WindField.Style(0.5, 0.5, 3.0, 18.0, 3.0, 1.8);
        double d = 40000;
        int calmNormal = 0;
        int calmSky = 0;
        int gustNormal = 0;
        int gustSky = 0;
        int gaps = 0;
        boolean wasCalm = false;
        for (long t = 0; t < 20L * 60 * 60; t++) {
            WindSample n = WindField.sample(p, d, t, SEED);
            WindSample k = WindField.sample(p, d, t, SEED, sky);
            if (n.isCalm()) calmNormal++;
            if (k.isCalm()) calmSky++;
            if (k.isCalm() && !wasCalm) gaps++;
            wasCalm = k.isCalm();
            if (n.gust01() > 0.05) gustNormal++;
            if (k.gust01() > 0.05) gustSky++;
        }
        assertTrue(calmSky > calmNormal + 1000, "sky has calm gaps: " + calmSky + " vs " + calmNormal);
        double avgGapSeconds = calmSky / 20.0 / Math.max(1, gaps);
        assertTrue(avgGapSeconds > 10.0 && avgGapSeconds < 25.0, "calm gaps last about 15 s: " + avgGapSeconds);
        assertTrue(gustSky < gustNormal * 0.7, "sky has fewer gusts: " + gustSky + " vs " + gustNormal);
        assertEquals(WindField.sample(p, d, 1234, SEED), WindField.sample(p, d, 1234, SEED, WindField.Style.NORMAL),
                "the normal style changes nothing");
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
        WindProfile p = new WindProfile(true, 90, 1, 1, 20, WindProfile.Multipliers.UNIT, WindProfile.Weight.NONE, new WindProfile.Curves(
                WindCurve.constant(0), WindCurve.constant(0), WindCurve.constant(6), WindCurve.constant(0.05),
                WindCurve.constant(1.0), WindCurve.constant(0), WindCurve.constant(0), WindCurve.constant(60)));
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
