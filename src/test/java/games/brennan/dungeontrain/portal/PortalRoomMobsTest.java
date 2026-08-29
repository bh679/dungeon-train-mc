package games.brennan.dungeontrain.portal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
 * <p>A retiring copy must take everything standing in <b>its own</b> volume and leave its
 * neighbour's standing. Get that wrong in one direction and mobs pile up behind the player for as
 * long as they keep walking — permanently, since they are all persistence-required; get it wrong in
 * the other and the room empties out around them. Neither is easy to spot in a live run, which is
 * why the membership rule is plain geometry and tested here rather than only in game.</p>
 */
class PortalRoomMobsTest {

    private static CompoundTag marked(int pairKey, int tileX, int tileZ) {
        CompoundTag data = new CompoundTag();
        PortalRoomMobs.mark(data, pairKey, new PortalRoomTiling.Tile(tileX, tileZ));
        return data;
    }

    /** A copy's box: eleven wide, seven tall, eleven deep, with its low corner at the origin. */
    private static BoundingBox copyBox(int x, int y, int z) {
        return new BoundingBox(x, y, z, x + 10, y + 6, z + 10);
    }

    @Test
    @DisplayName("A retiring copy sweeps what stands in it, whichever copy placed it")
    void sweepTakesTheWholeVolume() {
        BoundingBox box = copyBox(0, 40, 0);
        // The case the mark-scoped reap missed: a mob spawned by the copy next door that walked in
        // here. Membership is the volume, so it goes with the floor it is standing on.
        assertTrue(PortalRoomMobs.inside(box, 5.5, 41.0, 5.5));
        assertTrue(PortalRoomMobs.inside(box, 0.0, 40.0, 0.0), "the low corner is inside");
        assertTrue(PortalRoomMobs.inside(box, 10.9, 46.9, 10.9), "the high corner is inside");
    }

    @Test
    @DisplayName("A retiring copy leaves the copy next door alone")
    void sweepStopsAtTheSeam() {
        BoundingBox box = copyBox(0, 40, 0);
        // Copies abut, so a mob standing just over the shared wall has an AABB that touches this
        // box. Membership is decided on its position, or a retiring copy would empty the room the
        // player is about to walk back into.
        assertFalse(PortalRoomMobs.inside(box, 11.0, 41.0, 5.5), "the next copy along +x");
        assertFalse(PortalRoomMobs.inside(box, -0.5, 41.0, 5.5), "the next copy along -x");
        assertFalse(PortalRoomMobs.inside(box, 5.5, 41.0, 11.0), "the next copy along +z");
        assertFalse(PortalRoomMobs.inside(box, 5.5, 47.0, 5.5), "standing on the roof");
        assertFalse(PortalRoomMobs.inside(box, 5.5, 39.0, 5.5), "already below the floor");
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
        //
        // Pinned at ONE since the room's 2026-08-09 editing pass, down from two: the ceiling cell
        // 5,5,6 that carried the second warden was deleted in-world (that position reads
        // minecraft:air in distantenemies.nbt now), so its variant entry went with the block rather
        // than being dropped on save. The floor warden at 5,1,6, on sculk, is the one that remains.
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
            assertEquals(1, mobCells, "distantenemies is authored with one mob cell");
        }
    }
}
