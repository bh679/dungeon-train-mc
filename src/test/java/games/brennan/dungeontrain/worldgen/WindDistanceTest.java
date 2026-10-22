package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.wind.WindDistance;
import games.brennan.dungeontrain.wind.WindProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behind spawn the wind builds band by band walking -X, hitting each band's target at its +X edge. */
final class WindDistanceTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final long BUFFER = LAYOUT.length(0);

    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static final long WIND = C.upsideDownEndX();
    private static final WindProfile PROFILE = shipped();

    private static WindProfile shipped() {
        try (InputStream in = WindProfile.class.getResourceAsStream(WindProfile.RESOURCE_PATH)) {
            return WindProfile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** World X of the +X edge of the reversed copy of slot {@code i} on backward run {@code k}. */
    private static long bandTopX(int i, int k) {
        return START - BUFFER - CycleLayout.runStart(k + 1, P) + ((LAYOUT.start(i) + LAYOUT.length(i)) << k) - 1;
    }

    @Test
    @DisplayName("forward of spawn the distance is unchanged")
    void forward() {
        for (double x : new double[] {START, WIND - 10.5, WIND, WIND + 1234.25}) {
            assertEquals(x - WIND, WindDistance.at(PROFILE, C, x, WIND), 1e-9);
        }
        assertEquals(-1.0, WindDistance.at(PROFILE, C, WIND + 50, Long.MIN_VALUE));
    }

    @Test
    @DisplayName("the overworld buffer behind spawn is calm")
    void bufferCalm() {
        for (long x = START - BUFFER; x < START; x += 97) assertTrue(WindDistance.at(PROFILE, C, x, WIND) < 0);
    }

    @Test
    @DisplayName("each reversed band starts at its target")
    void hitsTargets() {
        int last = LAYOUT.count() - 1;
        assertEquals(PROFILE.reverseTarget(0), WindDistance.at(PROFILE, C, bandTopX(last, 0), WIND), 1.0);
        long n = 0;
        for (int i = last; i >= 0; i--) {
            if (LAYOUT.length(i) <= 0L) continue;
            assertEquals(PROFILE.reverseTarget(n), WindDistance.at(PROFILE, C, bandTopX(i, 0), WIND),
                    PROFILE.reverseTarget(n + 1) - PROFILE.reverseTarget(n) > 0 ? 25.0 : 1e-9, "band " + n);
            n++;
        }
    }

    @Test
    @DisplayName("walking -X over three reversed runs the wind distance never drops")
    void monotonic() {
        double prev = -1.0;
        for (long x = START - BUFFER - 1; x >= START - BUFFER - 3 * P; x -= 41) {
            double d = WindDistance.at(PROFILE, C, x, WIND);
            assertTrue(d >= prev - 1e-9, "dropped at " + x);
            prev = d;
        }
        assertTrue(prev > PROFILE.reverseTarget(20), "keeps climbing past the table");
    }

    @Test
    @DisplayName("past the table the last step repeats; a decreasing table is rejected")
    void table() {
        WindProfile p = WindProfile.parse("{\"curves\":" + curves() + ",\"reverseTargets\":[0,1000,3000]}");
        assertEquals(3000.0, p.reverseTarget(2));
        assertEquals(7000.0, p.reverseTarget(4));
        assertEquals(2000.0, p.reverseDistance(1, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> WindProfile.parse("{\"curves\":" + curves() + ",\"reverseTargets\":[0,5,4]}"));
        WindProfile none = WindProfile.parse("{\"curves\":" + curves() + "}");
        assertEquals(8000.0, none.reverseTarget(2));
    }

    private static String curves() {
        String c = "{\"kind\":\"log\",\"scale\":1,\"ref\":1}";
        StringBuilder b = new StringBuilder("{");
        for (String n : new String[] {"base", "flutter", "gustsPerMin", "gustStrength", "gustSeconds", "gustVeer", "wander", "wanderSeconds"}) {
            if (b.length() > 1) b.append(',');
            b.append('"').append(n).append("\":").append(c);
        }
        return b.append('}').toString();
    }
}
