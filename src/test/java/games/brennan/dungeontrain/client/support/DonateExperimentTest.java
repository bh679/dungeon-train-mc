package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Arm;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Experiment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bucketing: the decision that fixes what a given player sees for the life of an experiment.
 *
 * <p>Two failures matter more than the rest. A player whose arm drifts between deaths pollutes
 * every row they generate, so <b>stability</b> is tested first. And an experiment whose arms are
 * not actually evenly split silently biases the result it exists to produce, so the <b>split</b> is
 * measured over a real sample rather than assumed from the code.</p>
 */
class DonateExperimentTest {

    private static final List<String> KNOWN = DonateCards.knownArms();

    private static Experiment five() {
        return new Experiment("donate_cards_v1", "dt9f2c", List.of(
                new Arm("a_covered_updates", 20),
                new Arm("b_updates_hours", 20),
                new Arm("c_hours_active", 20),
                new Arm("d_active_raised", 20),
                new Arm("e_raised_covered", 20)));
    }

    // ---- stability ----

    @Test
    void theSamePlayerAlwaysDrawsTheSameArm() {
        UUID uuid = UUID.fromString("6f1c9a3e-0b2d-4c8f-9a71-2e5d8c4b1a09");
        String first = DonateExperiment.resolve(five(), uuid, KNOWN).arm();
        assertNotNull(first);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, DonateExperiment.resolve(five(), uuid, KNOWN).arm(),
                    "an arm that moves between deaths pollutes every row the player generates");
        }
    }

    @Test
    void rotatingTheSaltRebucketsThePopulation() {
        // The one supported way to re-run the same arms against a fresh population.
        int moved = 0;
        for (int i = 0; i < 200; i++) {
            UUID uuid = UUID.nameUUIDFromBytes(("salt-test-" + i).getBytes());
            String before = DonateExperiment.resolve(five(), uuid, KNOWN).arm();
            Experiment rotated = new Experiment("donate_cards_v1", "dt-new-salt", five().arms());
            if (!before.equals(DonateExperiment.resolve(rotated, uuid, KNOWN).arm())) moved++;
        }
        assertTrue(moved > 100, "a new salt should reshuffle most players, moved=" + moved);
    }

    // ---- the split ----

    @Test
    void equalWeightsSplitEvenlyOverARealSample() {
        Map<String, Integer> counts = sample(five(), 20_000);
        assertEquals(5, counts.size(), "every arm should be drawn by someone");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            // 20% of 20,000 is 4,000; ±10% relative is far tighter than any real traffic needs and
            // still leaves room for honest hash noise.
            assertTrue(e.getValue() > 3_600 && e.getValue() < 4_400,
                    "arm " + e.getKey() + " drew " + e.getValue() + " of 20000, expected ~4000");
        }
    }

    @Test
    void unequalWeightsAreHonoured() {
        Experiment exp = new Experiment("x", "s", List.of(
                new Arm("a_covered_updates", 90), new Arm("b_updates_hours", 10)));
        Map<String, Integer> counts = sample(exp, 10_000);
        assertTrue(counts.get("a_covered_updates") > 8_700, "heavy arm: " + counts);
        assertTrue(counts.get("b_updates_hours") > 700 && counts.get("b_updates_hours") < 1_300,
                "light arm: " + counts);
    }

    @Test
    void aZeroWeightArmIsNeverDrawn() {
        // How an operator kills one arm mid-flight without ending the experiment.
        Experiment exp = new Experiment("x", "s", List.of(
                new Arm("a_covered_updates", 50), new Arm("b_updates_hours", 0),
                new Arm("c_hours_active", 50)));
        assertFalse(sample(exp, 5_000).containsKey("b_updates_hours"));
    }

    // ---- degradation: every road leads to control ----

    @Test
    void noExperimentMeansNoAssignment() {
        assertFalse(DonateExperiment.resolve(null, UUID.randomUUID(), KNOWN).active());
    }

    @Test
    void noUuidMeansNoAssignment() {
        // An offline or unusual launcher has nothing stable to hash — control, and no telemetry
        // dimension, rather than a random arm that would change on the next launch.
        assertFalse(DonateExperiment.resolve(five(), null, KNOWN).active());
    }

    @Test
    void aMalformedExperimentMeansNoAssignment() {
        UUID uuid = UUID.randomUUID();
        assertFalse(DonateExperiment.resolve(
                new Experiment("", "s", five().arms()), uuid, KNOWN).active(), "blank id");
        assertFalse(DonateExperiment.resolve(
                new Experiment("x", "", five().arms()), uuid, KNOWN).active(), "blank salt");
        assertFalse(DonateExperiment.resolve(
                new Experiment("x", "s", List.of()), uuid, KNOWN).active(), "no arms");
        assertFalse(DonateExperiment.resolve(
                new Experiment("x", "s", List.of(new Arm("a_covered_updates", 1))), uuid, KNOWN).active(),
                "one arm is a rollout, not an experiment");
        assertFalse(DonateExperiment.resolve(
                new Experiment("x", "s", List.of(new Arm("a_covered_updates", 0),
                        new Arm("b_updates_hours", 0))), uuid, KNOWN).active(),
                "weights summing to zero");
    }

    @Test
    void armsThisJarCannotDrawAreExcludedBeforeTheSplit() {
        // A relay running a newer arm set must not hand this jar a layout it has no code for — and
        // must not quietly over-assign the arms it DOES know either. Dropping unknown arms before
        // weighting keeps the known arms' relative split intact.
        Experiment exp = new Experiment("x", "s", List.of(
                new Arm("a_covered_updates", 25),
                new Arm("f_something_new", 50),   // this jar predates it
                new Arm("b_updates_hours", 25)));
        Map<String, Integer> counts = sample(exp, 10_000);
        assertFalse(counts.containsKey("f_something_new"), "never drawn: " + counts);
        int a = counts.get("a_covered_updates");
        int b = counts.get("b_updates_hours");
        assertTrue(Math.abs(a - b) < 600, "the two known arms keep their 50/50 split: " + counts);
    }

    @Test
    void anExperimentOfEntirelyUnknownArmsMeansNoAssignment() {
        Experiment exp = new Experiment("x", "s", List.of(
                new Arm("f_new_one", 50), new Arm("g_new_two", 50)));
        assertNull(DonateExperiment.resolve(exp, UUID.randomUUID(), KNOWN).arm());
    }

    @Test
    void anAssignedArmIsAlwaysOneThisJarCanDraw() {
        for (int i = 0; i < 500; i++) {
            String arm = DonateExperiment.resolve(
                    five(), UUID.nameUUIDFromBytes(("draw-" + i).getBytes()), KNOWN).arm();
            assertTrue(KNOWN.contains(arm), arm + " is not drawable");
        }
    }

    private static Map<String, Integer> sample(Experiment exp, int n) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String arm = DonateExperiment.resolve(
                    exp, UUID.nameUUIDFromBytes(("player-" + i).getBytes()), KNOWN).arm();
            if (arm != null) counts.merge(arm, 1, Integer::sum);
        }
        return counts;
    }
}
