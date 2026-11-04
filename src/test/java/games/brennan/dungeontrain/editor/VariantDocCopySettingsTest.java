package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy roll and scope survive every kind's document, not just a track room's — the carriage
 * parser used to drop them, and the other three wrote their defaults.
 */
final class VariantDocCopySettingsTest {

    private static final BlockPos CELL = new BlockPos(1, 2, 3);
    private static final Vec3i BOX = new Vec3i(8, 8, 8);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<VariantState> states() {
        return List.of(VariantState.of(Blocks.STONE.defaultBlockState()),
            VariantState.of(Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    @DisplayName("roll and scope round-trip through a carriage-style (all-off mirror) document")
    void rollAndScopeRoundTrip() {
        VariantDoc doc = CarriageVariantBlocks.empty();
        doc.put(CELL, states());
        doc.setCopyRoll(CELL, VariantCopyRoll.VARY);
        doc.setCopyScope(CELL, VariantCopyScope.COPIES);
        String json = doc.toJsonText();
        assertTrue(json.contains("\"roll\": \"vary\"") && json.contains("\"scope\": \"copies\""), json);

        VariantDoc reloaded = VariantDoc.fromJsonText(json, MirrorDefaults.NONE, "carriage", BOX);
        assertEquals(VariantCopyRoll.VARY, reloaded.copyRollAt(CELL));
        assertEquals(VariantCopyScope.COPIES, reloaded.copyScopeAt(CELL));
    }

    @Test
    @DisplayName("the legacy reroll boolean reads as vary in every kind")
    void legacyRerollReads() {
        String legacy = """
            {
              "schemaVersion": 10,
              "variants": {
                "1,2,3": { "reroll": true, "states": ["minecraft:stone", "minecraft:cobblestone"] }
              }
            }
            """;
        VariantDoc reloaded = VariantDoc.fromJsonText(legacy, MirrorDefaults.NONE, "contents", BOX);
        assertEquals(VariantCopyRoll.VARY, reloaded.copyRollAt(CELL));
        assertFalse(reloaded.toJsonText().contains("reroll"));
    }

    @Test
    @DisplayName("a tunnel document omits the mirror field at X+Z, a carriage document at all-off")
    void mirrorDefaultsPerKind() {
        VariantDoc tunnel = VariantDoc.emptyWith(MirrorDefaults.TUNNEL, VariantPick.PLAIN, "tunnel");
        assertTrue(tunnel.mirrorX() && tunnel.mirrorZ() && tunnel.isDefaultMirror());
        assertFalse(tunnel.toJsonText().contains("mirror"));
        tunnel.setMirrorAxes(false, false, false);
        assertTrue(tunnel.toJsonText().contains("\"mirror\""));

        VariantDoc carriage = VariantDoc.empty();
        assertFalse(carriage.mirrorX());
        assertFalse(carriage.toJsonText().contains("mirror"));
    }

    @Test
    @DisplayName("a cropped view writes through to its whole document and removal clears every per-cell setting")
    void viewWritesThrough() {
        VariantDoc whole = VariantDoc.empty();
        whole.put(CELL, states());
        whole.put(new BlockPos(20, 0, 0), states());
        VariantDoc view = whole.croppedTo(BOX);
        assertTrue(view.isCropped());
        assertEquals(1, view.size());
        assertEquals(2, whole.size());

        view.setLockId(CELL, 3);
        view.setCopyRoll(CELL, VariantCopyRoll.EXACT);
        assertEquals(3, whole.lockIdAt(CELL));
        assertEquals(VariantCopyRoll.EXACT, whole.copyRollAt(CELL));
        assertTrue(view.whole() == whole);

        view.remove(CELL);
        assertEquals(1, whole.size());
        assertEquals(0, whole.lockIdAt(CELL));
        assertEquals(VariantCopyRoll.DEFAULT, whole.copyRollAt(CELL));
    }
}
