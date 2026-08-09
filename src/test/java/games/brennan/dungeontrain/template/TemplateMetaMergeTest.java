package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TemplateMeta#mergeWeight} / {@link TemplateMeta#mergeGate} are the merge decision the three
 * weight stores' {@code set(...)} / {@code setGate(...)} share. The point of the helpers is that a
 * weight or gate edit must <b>not</b> detach the entry from its Stage — before they existed each
 * store rebuilt the record through the 2-arg back-compat constructor, which passes
 * {@code stageId = null}, so nudging a weight on a Stage-linked editor row silently unlinked it.
 *
 * <p>The stores' setters themselves can't be exercised here — they persist through
 * {@code FMLPaths}, which these plain JUnit tests deliberately avoid bootstrapping (see
 * {@code CarriageWeightsTest}). Pulling the decision out is what makes it testable, the same reason
 * {@code StageStore.configDelta} is a pure static.</p>
 */
final class TemplateMetaMergeTest {

    private static final TemplateGate NETHER_GATE =
        new TemplateGate(3, 40, EnumSet.of(TrainPhase.NETHER));

    // ---------- mergeWeight ----------

    @Test
    @DisplayName("mergeWeight keeps the Stage link (the regression this guards)")
    void mergeWeight_keepsStageLink() {
        TemplateMeta prev = new TemplateMeta(4, NETHER_GATE, "nether");
        TemplateMeta next = TemplateMeta.mergeWeight(prev, 9);

        assertEquals(9, next.weight());
        assertEquals("nether", next.stageId(), "a weight edit must not detach the entry from its Stage");
    }

    @Test
    @DisplayName("mergeWeight keeps the inline gate snapshot untouched")
    void mergeWeight_keepsInlineGate() {
        TemplateMeta prev = new TemplateMeta(4, NETHER_GATE, "nether");
        assertEquals(NETHER_GATE, TemplateMeta.mergeWeight(prev, 9).gate());
    }

    @Test
    @DisplayName("mergeWeight on an unlinked entry stays unlinked")
    void mergeWeight_unlinkedStaysUnlinked() {
        TemplateMeta prev = new TemplateMeta(4, NETHER_GATE, null);
        TemplateMeta next = TemplateMeta.mergeWeight(prev, 9);

        assertEquals(9, next.weight());
        assertEquals(NETHER_GATE, next.gate());
        assertNull(next.stageId());
    }

    @Test
    @DisplayName("mergeWeight with no existing entry creates an unlinked default-gate entry")
    void mergeWeight_nullPrevCreatesDefault() {
        TemplateMeta next = TemplateMeta.mergeWeight(null, 9);

        assertEquals(9, next.weight());
        assertEquals(TemplateGate.DEFAULT, next.gate());
        assertNull(next.stageId());
    }

    // ---------- mergeGate ----------

    @Test
    @DisplayName("mergeGate keeps the Stage link (the regression this guards)")
    void mergeGate_keepsStageLink() {
        TemplateMeta prev = new TemplateMeta(4, TemplateGate.DEFAULT, "nether");
        TemplateMeta next = TemplateMeta.mergeGate(prev, NETHER_GATE, 1);

        assertEquals(NETHER_GATE, next.gate());
        assertEquals("nether", next.stageId(), "a gate edit must not detach the entry from its Stage");
    }

    @Test
    @DisplayName("mergeGate keeps the weight — including 0, which excludes the template from the pool")
    void mergeGate_keepsWeightIncludingZero() {
        assertEquals(4, TemplateMeta.mergeGate(
            new TemplateMeta(4, TemplateGate.DEFAULT, "nether"), NETHER_GATE, 1).weight());
        // 0 is meaningful (excluded from the pool), so it must not be mistaken for "absent"
        // and replaced by the store's DEFAULT of 1.
        assertEquals(0, TemplateMeta.mergeGate(
            new TemplateMeta(0, TemplateGate.DEFAULT, null), NETHER_GATE, 1).weight());
    }

    @Test
    @DisplayName("mergeGate with no existing entry creates an unlinked entry at the store's default weight")
    void mergeGate_nullPrevUsesDefaultWeight() {
        TemplateMeta next = TemplateMeta.mergeGate(null, NETHER_GATE, 1);

        assertEquals(1, next.weight());
        assertEquals(NETHER_GATE, next.gate());
        assertNull(next.stageId());
    }

    // ---------- why the helpers exist ----------

    @Test
    @DisplayName("The 2-arg back-compat constructor drops stageId — so setters must not rebuild through it")
    void twoArgConstructorDropsStageId() {
        // Pinned deliberately: this constructor is correct for its documented purpose (a fresh
        // unlinked entry) and is what the stores used to call with a Stage-linked prev in hand.
        // If a setter ever goes back to rebuilding from parts, the bug returns silently.
        assertNull(new TemplateMeta(4, NETHER_GATE).stageId());
    }
}
