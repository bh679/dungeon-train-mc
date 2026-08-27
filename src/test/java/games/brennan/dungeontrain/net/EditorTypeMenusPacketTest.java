package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round-trip for {@link EditorTypeMenusPacket}, focused on {@code helpPanelDismissed}.
 *
 * <p>The flag is written <em>before</em> the menu count — like {@code selectedStageId} — so the
 * {@link EditorTypeMenusPacket#empty()} snapshot stays buffer-symmetric. Reading it on the wrong
 * side of the "no menus" early-return would desync the buffer for every non-empty snapshot, which
 * is exactly the failure these tests pin.</p>
 */
final class EditorTypeMenusPacketTest {

    private static EditorTypeMenusPacket roundTrip(EditorTypeMenusPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        EditorTypeMenusPacket decoded = EditorTypeMenusPacket.decode(buf);
        assertFalse(buf.isReadable(), "decode must consume exactly what encode wrote");
        return decoded;
    }

    private static EditorTypeMenusPacket snapshot(boolean dismissed) {
        EditorTypeMenusPacket.Variant variant = new EditorTypeMenusPacket.Variant(
            "standard", 50, "carriages", "standard", "standard", true, false);
        EditorTypeMenusPacket.Menu menu = new EditorTypeMenusPacket.Menu(
            new BlockPos(12, 250, -30), "Carriages", List.of(variant), false,
            "carriages",
            List.of(new EditorTypeMenusPacket.CategoryButton("carriages", "Carriages")),
            List.of(new EditorTypeMenusPacket.TypeTab("Carriages", "carriages", "standard", "standard")));
        return new EditorTypeMenusPacket(List.of(menu), "night_market", dismissed);
    }

    @Test
    @DisplayName("round-trip preserves a dismissed Welcome panel alongside the menus")
    void roundTrip_dismissed() {
        EditorTypeMenusPacket decoded = roundTrip(snapshot(true));
        assertTrue(decoded.helpPanelDismissed());
        assertEquals("night_market", decoded.selectedStageId());
        assertEquals(1, decoded.menus().size());
        assertEquals("Carriages", decoded.menus().get(0).typeName());
        assertEquals("standard", decoded.menus().get(0).variants().get(0).name());
    }

    @Test
    @DisplayName("round-trip preserves a visible Welcome panel")
    void roundTrip_visible() {
        EditorTypeMenusPacket decoded = roundTrip(snapshot(false));
        assertFalse(decoded.helpPanelDismissed());
        assertEquals(1, decoded.menus().size());
    }

    @Test
    @DisplayName("the empty clear snapshot round-trips with the flag off")
    void roundTrip_empty() {
        EditorTypeMenusPacket decoded = roundTrip(EditorTypeMenusPacket.empty());
        assertTrue(decoded.isEmpty());
        assertFalse(decoded.helpPanelDismissed());
        assertEquals("", decoded.selectedStageId());
    }

    @Test
    @DisplayName("an empty menu list still carries the flag across the wire")
    void roundTrip_emptyMenusKeepsFlag() {
        EditorTypeMenusPacket decoded = roundTrip(
            new EditorTypeMenusPacket(List.of(), "", true));
        assertTrue(decoded.isEmpty());
        assertTrue(decoded.helpPanelDismissed());
    }

    @Test
    @DisplayName("the two-arg constructor defaults to a visible panel")
    void legacyConstructorDefault() {
        assertFalse(new EditorTypeMenusPacket(List.of(), "").helpPanelDismissed());
    }
}
