package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@code /restore} wire shape the relay and every shipped jar share. Anything the game
 * doesn't understand must parse to "no restore", never to a guess.
 */
final class InventoryRestoreClientTest {


    @Test
    @DisplayName("a pending restore parses its id, every slot group and the optional XP")
    void parseRestore() {
        String body = "{\"ok\":true,\"restore\":{\"id\":\"r_1a2b\","
            + "\"inventory\":[{\"slot\":0,\"id\":\"minecraft:netherite_sword\",\"count\":1,\"nbt\":\"{id:\\\"minecraft:netherite_sword\\\",count:1}\"}],"
            + "\"armor\":[{\"slot\":103,\"id\":\"minecraft:netherite_helmet\",\"count\":1,\"nbt\":\"{id:\\\"minecraft:netherite_helmet\\\",count:1}\"}],"
            + "\"offhand\":[],\"xpLevels\":32}}";
        InventoryRestoreClient.Restore r = InventoryRestoreClient.parse(body);
        assertNotNull(r);
        assertEquals("r_1a2b", r.id());
        assertEquals(1, r.inventory().size());
        assertEquals(0, r.inventory().get(0).slot());
        assertEquals("{id:\"minecraft:netherite_sword\",count:1}", r.inventory().get(0).nbt());
        assertEquals(103, r.armor().get(0).slot());
        assertEquals(32, r.xpLevels());
        assertEquals(2, r.all().size());
    }

    @Test
    @DisplayName("no restore, a not-ok body, a blank id or an empty restore all mean nothing to apply")
    void parseNothing() {
        assertNull(InventoryRestoreClient.parse("{\"ok\":true,\"restore\":null}"));
        assertNull(InventoryRestoreClient.parse("{\"ok\":true}"));
        assertNull(InventoryRestoreClient.parse("{\"ok\":false,\"restore\":{\"id\":\"r\",\"inventory\":[]}}"));
        assertNull(InventoryRestoreClient.parse("{\"ok\":true,\"restore\":{\"id\":\"\",\"xpLevels\":3}}"));
        assertNull(InventoryRestoreClient.parse("{\"ok\":true,\"restore\":{\"id\":\"r\",\"inventory\":[]}}"));
        assertNull(InventoryRestoreClient.parse("[]"));
    }

    @Test
    @DisplayName("a stack without a slot or SNBT is dropped rather than guessed at")
    void parseDropsIncompleteStacks() {
        InventoryRestoreClient.Restore r = InventoryRestoreClient.parse(
            "{\"ok\":true,\"restore\":{\"id\":\"r\",\"inventory\":["
                + "{\"id\":\"minecraft:dirt\",\"count\":1,\"nbt\":\"{}\"},"
                + "{\"slot\":2,\"id\":\"minecraft:dirt\",\"count\":1},"
                + "{\"slot\":3,\"id\":\"minecraft:dirt\",\"count\":1,\"nbt\":\"{id:\\\"minecraft:dirt\\\",count:1}\"}]}}");
        assertNotNull(r);
        assertEquals(1, r.inventory().size());
        assertEquals(3, r.inventory().get(0).slot());
        assertEquals(-1, r.xpLevels());
    }

    @Test
    @DisplayName("the ack carries a dashless uuid, the state and the counts")
    void ackBody() {
        UUID uuid = UUID.fromString("7b7774bd-61fc-44b5-9b8b-3f38ce70a443");
        JsonObject o = JsonParser.parseString(InventoryRestoreClient.ackBody(uuid, "r_1",
            new InventoryRestoreClient.Outcome(true, null, 23, 2, 0))).getAsJsonObject();
        assertEquals("7b7774bd61fc44b59b8b3f38ce70a443", o.get("uuid").getAsString());
        assertEquals("r_1", o.get("id").getAsString());
        assertEquals("applied", o.get("state").getAsString());
        assertEquals(23, o.get("placed").getAsInt());
        assertEquals(2, o.get("displaced").getAsInt());
        assertEquals(0, o.get("skipped").getAsInt());
        assertFalse(o.has("reason"));

        JsonObject declined = JsonParser.parseString(InventoryRestoreClient.ackBody(uuid, "r_1",
            new InventoryRestoreClient.Outcome(false, "nope", 0, 0, 0))).getAsJsonObject();
        assertEquals("declined", declined.get("state").getAsString());
        assertEquals("nope", declined.get("reason").getAsString());
    }
}
