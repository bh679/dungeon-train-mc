package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.EditorCategory;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers {@link EditorPlotActionPacket#resolve(String)} — the category resolution that
 * decides whether a Save / Reset / Clear / Enter click is acted on.
 *
 * <p>Pins the two ways the old {@code EditorCategory.valueOf(packet.category)} silently
 * dropped an action: it was case-sensitive, so the lowercase spelling used by the keyboard
 * menus never routed; and {@code EditorCategory} has no PARTS constant, so a parts row threw
 * and vanished into the same "unknown category" branch. A parts action is still refused —
 * parts really have no action row — but now deliberately rather than by accident, and the
 * client no longer sends one at all.</p>
 */
final class EditorPlotActionPacketTest {

    private static EditorPlotActionPacket roundTrip(EditorPlotActionPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        EditorPlotActionPacket decoded = EditorPlotActionPacket.decode(buf);
        assertFalse(buf.isReadable(), "decode must consume exactly what encode wrote");
        return decoded;
    }

    @Test
    @DisplayName("round-trip preserves category, model and action")
    void roundTrip_preservesFields() {
        EditorPlotActionPacket decoded = roundTrip(new EditorPlotActionPacket(
            "PORTALS", "portal_room", "library", EditorPlotActionPacket.Action.RESET));
        assertEquals("PORTALS", decoded.category());
        assertEquals("portal_room", decoded.modelId());
        assertEquals("library", decoded.modelName());
        assertSame(EditorPlotActionPacket.Action.RESET, decoded.action());
    }

    @Test
    @DisplayName("resolve: the four actionable categories map to their plot set")
    void resolve_actionableCategories() {
        assertSame(EditorCategory.CARRIAGES, EditorPlotActionPacket.resolve("CARRIAGES"));
        assertSame(EditorCategory.CONTENTS, EditorPlotActionPacket.resolve("CONTENTS"));
        assertSame(EditorCategory.TRACKS, EditorPlotActionPacket.resolve("TRACKS"));
        assertSame(EditorCategory.PORTALS, EditorPlotActionPacket.resolve("PORTALS"));
    }

    @Test
    @DisplayName("resolve: lowercase now routes — it used to be dropped as unknown")
    void resolve_isCaseInsensitive() {
        // EditorCategory.valueOf("carriages") threw, so every lowercase category — the spelling
        // the keyboard menus and slash commands use — was logged unknown and the click lost.
        assertSame(EditorCategory.CARRIAGES, EditorPlotActionPacket.resolve("carriages"));
        assertSame(EditorCategory.PORTALS, EditorPlotActionPacket.resolve("Portals"));
    }

    @Test
    @DisplayName("resolve: parts are refused deliberately, not by throwing")
    void resolve_refusesParts() {
        // Parts are saved by position (runPartSave reads the plot the player is in) rather than
        // addressed by (modelId, modelName), so there is no action to dispatch. Previously this
        // was an IllegalArgumentException caught as "unknown category".
        assertNull(EditorPlotActionPacket.resolve("PARTS"));
        assertNull(EditorPlotActionPacket.resolve("parts"));
    }

    @Test
    @DisplayName("resolve: unknown, blank and sentinel values yield nothing to dispatch")
    void resolve_rejectsNonCategories() {
        assertNull(EditorPlotActionPacket.resolve(""));
        assertNull(EditorPlotActionPacket.resolve(null));
        assertNull(EditorPlotActionPacket.resolve("nonsense"));
        // The Stages panel's sentinel is not a plot category.
        assertNull(EditorPlotActionPacket.resolve("stages"));
        // ARCHITECTURE parses but has no models, so there is nothing to act on.
        assertNull(EditorPlotActionPacket.resolve("ARCHITECTURE"));
    }
}
