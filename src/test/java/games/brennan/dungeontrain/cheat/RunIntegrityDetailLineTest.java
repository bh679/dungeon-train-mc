package games.brennan.dungeontrain.cheat;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The specifics line under a Free Play cause — "which settings changed", "which cheat mod" — as
 * built by {@link RunIntegrity#detailLine}. A retuned config can deviate on dozens of keys, so the
 * line lists a few and counts the rest rather than growing a tooltip past the screen. The cause
 * resolution around it needs a real player attachment and is verified in-game.
 */
class RunIntegrityDetailLineTest {

    private static TranslatableContents contents(Component component) {
        return (TranslatableContents) component.getContents();
    }

    @Test
    @DisplayName("No specifics (a game-mode switch, a retuned portal rate) means no detail line")
    void emptyMeansNoLine() {
        assertNull(RunIntegrity.detailLine(List.of()));
        assertNull(RunIntegrity.detailLine(null));
    }

    @Test
    @DisplayName("Up to three items are listed in full")
    void shortListsAreListed() {
        Component line = RunIntegrity.detailLine(List.of("mobHealth", "lootRate", "portalRate"));
        assertNotNull(line);
        assertEquals("effect.dungeontrain.free_play.trigger.detail", contents(line).getKey());
        assertArrayEquals(new Object[]{"mobHealth, lootRate, portalRate"}, contents(line).getArgs());
    }

    @Test
    @DisplayName("A single item still reads as a plain list, not a count")
    void singleItem() {
        Component line = RunIntegrity.detailLine(List.of("Xaero's Minimap"));
        assertNotNull(line);
        assertEquals("effect.dungeontrain.free_play.trigger.detail", contents(line).getKey());
        assertArrayEquals(new Object[]{"Xaero's Minimap"}, contents(line).getArgs());
    }

    @Test
    @DisplayName("Past three, the first three are named and the remainder is counted")
    void longListsAreCapped() {
        Component line = RunIntegrity.detailLine(List.of("a", "b", "c", "d", "e"));
        assertNotNull(line);
        assertEquals("effect.dungeontrain.free_play.trigger.detail_more", contents(line).getKey());
        assertArrayEquals(new Object[]{"a, b, c", 2}, contents(line).getArgs());
    }

    @Test
    @DisplayName("Exactly four caps rather than listing — the boundary is three")
    void boundaryAtFour() {
        Component line = RunIntegrity.detailLine(List.of("a", "b", "c", "d"));
        assertNotNull(line);
        assertEquals("effect.dungeontrain.free_play.trigger.detail_more", contents(line).getKey());
        assertArrayEquals(new Object[]{"a, b, c", 1}, contents(line).getArgs());
    }

    @Test
    @DisplayName("A cause survives the save round-trip with its key and args — it is stored, not resolved")
    void causeRoundTripsThroughNbt() {
        // What ModDataAttachments.FREE_PLAY_CAUSE does on world save/load. Storing the Component
        // rather than its resolved text is the whole reason the reader gets it in THEIR language,
        // so the codec has to survive the trip with the arguments intact.
        Component cause = Component.translatable(
            "chat.dungeontrain.free_play.cause.command", "/give @s diamond 64");

        DataResult<Tag> written = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, cause);
        assertTrue(written.isSuccess(), () -> "encode failed: " + written.error());

        DataResult<Component> read = ComponentSerialization.CODEC
            .parse(NbtOps.INSTANCE, written.getOrThrow());
        assertTrue(read.isSuccess(), () -> "decode failed: " + read.error());

        Component restored = read.getOrThrow();
        assertEquals("chat.dungeontrain.free_play.cause.command", contents(restored).getKey());
        assertArrayEquals(new Object[]{"/give @s diamond 64"}, contents(restored).getArgs());
    }
}
