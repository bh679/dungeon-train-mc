package games.brennan.dungeontrain.net.relay;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Goal;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Summary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the death-screen ledger's wire shape. {@link #realRelayBody} is a verbatim
 * {@code GET /<CAP>/donations/summary} response from dp-relay's own {@code buildSummary} (seeded:
 * $40 disclosed cost, $155 raised, so the running-cost rung is covered and the ask has moved to
 * dev tools) — the payload is the one contract the relay and every shipped jar share, and a jar
 * can never be updated to match a relay that changes it.
 */
final class DonationSummaryClientTest {

    /** Verbatim relay output — do not hand-tidy; it is here to catch drift, not to look neat. */
    private static String realRelayBody() {
        return """
        {"ok":true,"period":"2026-07","currency":"AUD","fxRate":1.42,"fxAsOf":null,
         "fxSource":"fallback","monthlyRaisedUsd":155,"totalRaisedUsd":155,"monthlyCostUsd":40,
         "monthlyCostBasis":"projected","percentCovered":388,
         "goals":[{"id":"running_costs","label":"Running Costs","targetAud":40,"raisedAud":40,
                   "percent":100,"complete":true},
                  {"id":"dev_tools","label":"Dev Tools","targetAud":280,"raisedAud":115,
                   "percent":41,"complete":false}],
         "activeGoalId":"dev_tools",
         "patrons":{"count":3,"monthlyUsd":50,"paidThisMonthUsd":50},
         "sources":{"revolutUsd":105,"stripeUsd":0,"patreonUsd":50,"otherUsd":0},"feesUsd":0,
         "monthly":[{"name":"Alice","source":"revolut","amountUsd":105},
                    {"name":"Pat","source":"patreon","amountUsd":10}],
         "allTime":[{"name":"Alice","source":"revolut","amountUsd":105}],
         "you":{"monthlyUsd":105,"totalUsd":105}}
        """;
    }

    @Test
    @DisplayName("the updates block parses, and its absence is not a failure")
    void parsesTheUpdatesBlock() {
        // The relay adds this once its own poll of the version history resolves; a body without
        // it is the normal case for every relay that predates the field.
        assertNull(DonationSummaryClient.parse(realRelayBody()).updates(),
                "no updates block means the screen uses the jar's baked numbers");

        Summary s = DonationSummaryClient.parse("""
        {"ok":true,"monthlyRaisedUsd":155,"monthlyCostUsd":40,
         "updates":{"count":765,"windowMonths":5,"month":244,"week":117,
                    "latestReleaseAt":1756728000000,"latestVersion":"0.763.0"}}
        """);
        assertEquals(765, s.updates().count());
        assertEquals(5, s.updates().windowMonths());
        assertEquals(244, s.updates().month());
        assertEquals(117, s.updates().week());
        assertEquals(1756728000000L, s.updates().latestReleaseAtMs());
        assertEquals("0.763.0", s.updates().latestVersion());
    }

    @Test
    @DisplayName("an updates block the relay could not fill is treated as absent")
    void anEmptyUpdatesBlockIsAbsent() {
        // windowMonths 0 is meaningful ("this year"), so the count is what decides. A block whose
        // count never resolved must fall through to the baked numbers rather than render a zero.
        assertNull(DonationSummaryClient.parse("""
        {"ok":true,"updates":{"count":null,"windowMonths":0,"month":0}}
        """).updates());
        assertNull(DonationSummaryClient.parse("""
        {"ok":true,"updates":[]}
        """).updates());
    }

    @Test
    @DisplayName("a real relay body parses into the ledger the screen draws")
    void parsesARealRelayBody() {
        Summary s = DonationSummaryClient.parse(realRelayBody());
        assertEquals(155, s.monthlyRaisedUsd());
        assertEquals(40, s.monthlyCostUsd());
        assertEquals(388, s.percentCovered());
        assertEquals(3, s.patronCount());
        assertEquals(2, s.monthly().size());
        assertTrue(s.hasYou());
        assertEquals(105, s.youMonthlyUsd());
    }

    @Test
    @DisplayName("the support ladder arrives in relay order, with its active rung named")
    void parsesTheSupportLadder() {
        Summary s = DonationSummaryClient.parse(realRelayBody());
        assertEquals(2, s.goals().size());
        assertEquals("dev_tools", s.activeGoalId());

        Goal costs = s.goals().get(0);
        assertEquals("running_costs", costs.id());
        assertEquals(100, costs.percent());
        assertTrue(costs.complete());

        Goal tools = s.goals().get(1);
        assertEquals("dev_tools", tools.id());
        assertEquals("Dev Tools", tools.label());   // the fallback name for jars without a key
        assertEquals(280, tools.targetAud());
        assertEquals(115, tools.raisedAud());
        assertEquals(41, tools.percent());
    }

    @Test
    @DisplayName("a relay predating the ladder yields no goals, not a crash")
    void toleratesAPayloadWithoutGoals() {
        Summary s = DonationSummaryClient.parse(
                "{\"ok\":true,\"monthlyRaisedUsd\":10,\"monthlyCostUsd\":40,\"percentCovered\":25}");
        assertTrue(s.goals().isEmpty());
        assertNull(s.activeGoalId());
        assertEquals(25, s.percentCovered());   // the pre-ladder tile still has its figure
    }

    @Test
    @DisplayName("no cost snapshot: the cost figures read as unknown and the ladder is empty")
    void toleratesAnUnknownCost() {
        Summary s = DonationSummaryClient.parse(
                "{\"ok\":true,\"monthlyRaisedUsd\":10,\"monthlyCostUsd\":null,"
                        + "\"percentCovered\":null,\"goals\":[],\"activeGoalId\":null}");
        assertEquals(-1, s.monthlyCostUsd());     // -1 is the screen's "—"
        assertEquals(-1, s.percentCovered());
        assertTrue(s.goals().isEmpty());
    }

    @Test
    @DisplayName("a malformed goal is dropped rather than drawn as a nameless rung")
    void dropsGoalsWithoutAnId() {
        Summary s = DonationSummaryClient.parse(
                "{\"ok\":true,\"monthlyCostUsd\":40,\"goals\":[{\"label\":\"No id\"},"
                        + "{\"id\":\"\",\"label\":\"Blank id\"},\"not an object\","
                        + "{\"id\":\"dev_tools\",\"percent\":41}],\"activeGoalId\":\"dev_tools\"}");
        assertEquals(1, s.goals().size());
        Goal only = s.goals().get(0);
        assertEquals("dev_tools", only.id());
        assertEquals("dev_tools", only.label());  // no label sent → the id stands in
        assertEquals(41, only.percent());
        assertEquals(0, only.targetAud());
    }

    @Test
    @DisplayName("a non-ok or non-object body is no summary at all")
    void rejectsBodiesThatAreNotAnOkSummary() {
        assertNull(DonationSummaryClient.parse("{\"error\":\"unauthorized\"}"));
        assertNull(DonationSummaryClient.parse("[]"));
    }
}
