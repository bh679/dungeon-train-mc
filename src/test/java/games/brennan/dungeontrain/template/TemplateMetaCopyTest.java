package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TemplateMeta#asCopy} is the one rule the three weight stores' {@code copy(from, to)} share:
 * what a duplicate inherits from its source's {@code weights.json} entry.
 *
 * <p>The stores' {@code copy} methods themselves persist through {@code FMLPaths}, which these plain
 * JUnit tests deliberately avoid bootstrapping (see {@code TemplateMetaMergeTest}) — so the decision
 * is pulled out and tested here. The field that matters most is {@code mode}: for a portal room it
 * is the sky, walls, copies and door settings, and before the editor's New carried it a copy came up
 * as a bare box.</p>
 */
final class TemplateMetaCopyTest {

    private static final TemplateGate NETHER_GATE =
        new TemplateGate(3, 40, EnumSet.of(TrainPhase.NETHER));

    /** A portal room's full boundary tag — sky, walls, copies, exits, books, door wall, offsets. */
    private static final String ROOM_MODE = "bedrock_lock/exact/off/off/off/none/sealed/-1";

    @Test
    @DisplayName("asCopy keeps weight, gate, Stage link, mode, flip and builder — the room's settings travel")
    void asCopy_keepsEverySpawnRule() {
        BuilderCredit mika = new BuilderCredit("380df991f603344ca090369bad2a924a", "Mika");
        TemplateMeta source = new TemplateMeta(4, NETHER_GATE, "nether", ROOM_MODE,
            FlipOptions.DEFAULT.with("x", true), "Hell Hall", mika);

        TemplateMeta copy = source.asCopy();

        assertEquals(4, copy.weight());
        assertEquals(NETHER_GATE, copy.gate());
        assertEquals("nether", copy.stageId());
        assertEquals(ROOM_MODE, copy.mode());
        assertEquals(source.effectiveFlip(), copy.effectiveFlip());
        assertEquals(mika, copy.builder());
    }

    @Test
    @DisplayName("asCopy drops the display label — two templates must not answer to one name")
    void asCopy_dropsTheLabel() {
        TemplateMeta source = new TemplateMeta(1, TemplateGate.DEFAULT, null, ROOM_MODE, null, "Hell Hall");

        assertNull(source.asCopy().name());
        // The source is a record; asCopy is a copy, not an edit.
        assertEquals("Hell Hall", source.name());
    }

    @Test
    @DisplayName("asCopy of a label-less entry is the entry itself")
    void asCopy_ofUnlabelledIsIdentity() {
        TemplateMeta source = new TemplateMeta(2, NETHER_GATE, "nether", ROOM_MODE);
        assertEquals(source, source.asCopy());
    }
}
