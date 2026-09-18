package games.brennan.dungeontrain.portal;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage a portal part was stamped for is recorded beside the stamp record and read back as
 * recorded — never re-derived. These pin the three answers a reader can get (unrecorded, default
 * palette, a stage), that an ordinary re-stamp forgets it, and that it survives a save.
 */
final class PortalRegistryStampedStagesTest {

    private static PortalRegistry fresh() {
        return PortalRegistry.load(new CompoundTag());
    }

    @Test
    @DisplayName("a stamped portal part reads back the stage it was stamped for")
    void recordsTheStage() {
        PortalRegistry registry = fresh();
        registry.noteStamped(12, true, "nether");

        assertEquals(Optional.of("nether"), registry.stampedStageOf(12));
        assertTrue(registry.isStampedPortalPart(12));
    }

    @Test
    @DisplayName("default palette is recorded as present-and-empty, distinct from unrecorded")
    void defaultPaletteIsNotUnrecorded() {
        PortalRegistry registry = fresh();
        registry.noteStamped(12, true, null);

        assertEquals(Optional.of(""), registry.stampedStageOf(12), "recorded: resolved through the default palette");
        assertEquals(Optional.empty(), registry.stampedStageOf(13), "never stamped: nothing to say");
    }

    @Test
    @DisplayName("an index re-stamped as an ordinary carriage forgets its stage with its record")
    void ordinaryRestampForgets() {
        PortalRegistry registry = fresh();
        registry.noteStamped(12, true, "desert");
        registry.noteStamped(12, false);

        assertFalse(registry.isStampedPortalPart(12));
        assertEquals(Optional.empty(), registry.stampedStageOf(12));
    }

    @Test
    @DisplayName("the two-argument form records the part but no stage")
    void twoArgFormRecordsNoStage() {
        PortalRegistry registry = fresh();
        registry.noteStamped(12, true);

        assertTrue(registry.isStampedPortalPart(12));
        assertEquals(Optional.of(""), registry.stampedStageOf(12),
            "a legacy confirm records the part; nothing knows its stage, so it reads as default");
    }

    @Test
    @DisplayName("stages survive a save and load")
    void roundTrips() {
        PortalRegistry registry = fresh();
        registry.noteStamped(3, true, "stone");
        registry.noteStamped(-6, true, null);

        PortalRegistry reloaded = PortalRegistry.load(registry.save(new CompoundTag(), null));

        assertEquals(Optional.of("stone"), reloaded.stampedStageOf(3));
        assertEquals(Optional.of(""), reloaded.stampedStageOf(-6));
        assertEquals(Optional.empty(), reloaded.stampedStageOf(4));
    }

    @Test
    @DisplayName("a world saved before the record existed loads as unrecorded")
    void legacyTagLoadsUnrecorded() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("stampedPortalParts", new int[] {12});
        PortalRegistry registry = PortalRegistry.load(tag);

        assertTrue(registry.isStampedPortalPart(12));
        assertEquals(Optional.empty(), registry.stampedStageOf(12), "falls back to the formula");
    }
}
