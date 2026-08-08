package games.brennan.dungeontrain.portal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that keeps a repeating room's authored mobs from becoming a leak.
 *
 * <p>A copy of the room must reap the mobs <b>it</b> placed and leave its neighbour's standing. Get
 * that wrong in one direction and mobs pile up behind the player for as long as they keep walking;
 * get it wrong in the other and the room empties out around them. Neither is easy to spot in a live
 * run, which is why the mark is plain NBT and tested here rather than only in game.</p>
 */
class PortalRoomMobsTest {

    private static CompoundTag marked(int pairKey, int tileX, int tileZ) {
        CompoundTag data = new CompoundTag();
        PortalRoomMobs.mark(data, pairKey, new PortalRoomTiling.Tile(tileX, tileZ));
        return data;
    }

    @Test
    @DisplayName("A copy reaps the mobs it placed")
    void marksRoundTrip() {
        CompoundTag data = marked(6, 2, -3);
        assertTrue(PortalRoomMobs.marks(data, 6, new PortalRoomTiling.Tile(2, -3)));
        assertEquals(6, PortalRoomMobs.markedPair(data));
    }

    @Test
    @DisplayName("A copy leaves its neighbours' mobs alone — every direction, including the diagonal")
    void neighbouringCopiesDoNotReapEachOther() {
        CompoundTag data = marked(6, 2, -3);
        int[][] neighbours = {{1, -3}, {3, -3}, {2, -2}, {2, -4}, {1, -2}, {3, -4}, {0, 0}};
        for (int[] n : neighbours) {
            assertFalse(PortalRoomMobs.marks(data, 6, new PortalRoomTiling.Tile(n[0], n[1])),
                "copy " + n[0] + "," + n[1] + " must not reap a mob placed by copy 2,-3");
        }
    }

    @Test
    @DisplayName("One portal never reaps another portal's mobs, even at the same copy coordinates")
    void otherPairsDoNotReap() {
        CompoundTag data = marked(6, 0, 0);
        // Two structures share a Y lane and can tile toward each other, so the same tile coordinates
        // genuinely do occur for different pairs. The pair key is what separates them.
        for (int otherPair : new int[]{0, 12, -6, 7}) {
            assertFalse(PortalRoomMobs.marks(data, otherPair, PortalRoomTiling.Tile.BASE),
                "pair " + otherPair + " must not reap pair 6's mobs");
        }
    }

    @Test
    @DisplayName("An unmarked entity belongs to no portal — a wanderer is never reaped as ours")
    void unmarkedBelongsToNobody() {
        CompoundTag data = new CompoundTag();
        assertFalse(PortalRoomMobs.marks(data, 0, PortalRoomTiling.Tile.BASE));
        assertEquals(Integer.MIN_VALUE, PortalRoomMobs.markedPair(data));
        // Pair 0 and tile 0,0 are both real values, so "absent" must not read as "matches zero".
        assertFalse(PortalRoomMobs.marks(data, 0, new PortalRoomTiling.Tile(0, 0)));
    }

    @Test
    @DisplayName("A relocation reaps every copy's mobs, and only this pair's")
    void relocationReapsThePairNotTheTile() {
        // reapPair's rule, at the tag level: the pair mark alone, whatever copy placed the mob. A
        // relocating structure takes its whole room with it — the tiles are about to stop existing —
        // and the stamp at the new site rolls a fresh set, so anything left marked would stand next
        // to its own replacement.
        for (int[] tile : new int[][]{{0, 0}, {4, 1}, {-2, -5}}) {
            CompoundTag data = marked(6, tile[0], tile[1]);
            assertEquals(6, PortalRoomMobs.markedPair(data),
                "a mob placed by copy " + tile[0] + "," + tile[1] + " goes with pair 6's room");
        }

        // What the carry is for: a villager or pet led in by a player has no mark, and neither does
        // another pair's mob standing in the box where two structures share a Y lane.
        assertEquals(Integer.MIN_VALUE, PortalRoomMobs.markedPair(new CompoundTag()));
        assertEquals(7, PortalRoomMobs.markedPair(marked(7, 0, 0)));
    }

    @Test
    @DisplayName("The cap admits up to its ceiling and refuses past it")
    void capRefusesPastTheCeiling() {
        assertTrue(PortalRoomMobs.withinCap(0));
        assertTrue(PortalRoomMobs.withinCap(PortalRoomMobs.MAX_LIVE_PER_STRUCTURE - 1));
        assertFalse(PortalRoomMobs.withinCap(PortalRoomMobs.MAX_LIVE_PER_STRUCTURE));
        assertFalse(PortalRoomMobs.withinCap(PortalRoomMobs.MAX_LIVE_PER_STRUCTURE + 10));
    }

    @Test
    @DisplayName("distantenemies still authors its mob cells")
    void shippedRoomStillHasItsMobs() throws Exception {
        // The room that prompted this work. A re-save that silently dropped the entity entries would
        // look exactly like the spawn being broken again, so pin the data rather than the code alone.
        try (InputStream in = PortalRoomMobsTest.class.getResourceAsStream(
                "/data/dungeontrain/portals/room/distantenemies.variants.json")) {
            assertNotNull(in, "distantenemies.variants.json must ship");
            JsonObject json = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();

            int mobCells = 0;
            for (Map.Entry<String, com.google.gson.JsonElement> cell
                    : json.getAsJsonObject("variants").entrySet()) {
                // A cell is either a bare list of states, or an object wrapping `states` with the
                // lock id that ties several cells to one roll. Mob entries only appear in the list.
                com.google.gson.JsonElement value = cell.getValue();
                if (!value.isJsonArray()) continue;
                for (com.google.gson.JsonElement state : value.getAsJsonArray()) {
                    if (state.getAsJsonObject().has("entity")) mobCells++;
                }
            }
            assertEquals(2, mobCells, "distantenemies is authored with two mob cells");
        }
    }
}
