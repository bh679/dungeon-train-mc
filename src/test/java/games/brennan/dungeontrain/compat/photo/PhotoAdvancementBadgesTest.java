package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.compat.photo.PhotoAdvancementBadges.Badge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoAdvancementBadgesTest {

    private static JsonArray json(String s) { return JsonParser.parseString(s).getAsJsonArray(); }

    @Test
    @DisplayName("the relay's list keeps well-formed DT ids, once each, in order")
    void idsFromRelay() {
        assertEquals(List.of("dungeontrain:enchiridion/say_cheese", "dungeontrain:enchiridion/shh"),
                PhotoAdvancementBadges.idsFrom(json("[\"dungeontrain:enchiridion/say_cheese\", \"minecraft:story/root\", 7,"
                        + " \"dungeontrain:Bad Id\", \"dungeontrain:enchiridion/say_cheese\", \"dungeontrain:enchiridion/shh\"]")));
        assertEquals(List.of(), PhotoAdvancementBadges.idsFrom(null));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 30; i++) many.append(i == 0 ? "" : ",").append("\"dungeontrain:enchiridion/a").append(i).append('"');
        assertEquals(PhotoAdvancementBadges.MAX, PhotoAdvancementBadges.idsFrom(json(many + "]")).size());
    }

    @Test
    @DisplayName("unknown advancements are left out when resolving")
    void resolveSkipsUnknown() {
        List<Badge> out = PhotoAdvancementBadges.resolveAll(
                List.of("dungeontrain:enchiridion/say_cheese", "dungeontrain:enchiridion/gone"),
                id -> id.endsWith("gone") ? Optional.empty() : Optional.of(new Badge(id, "minecraft:bone", "task")));
        assertEquals(List.of(new Badge("dungeontrain:enchiridion/say_cheese", "minecraft:bone", "task")), out);
    }

    @Test
    @DisplayName("badges round-trip through a frame's extra data; none writes nothing")
    void roundTrip() {
        List<Badge> badges = List.of(
                new Badge("dungeontrain:enchiridion/say_cheese", "exposure_polaroid:instant_camera", "task"),
                new Badge("dungeontrain:enchiridion/never_dies", "minecraft:golden_helmet", "challenge"));
        CompoundTag tag = new CompoundTag();
        PhotoAdvancementBadges.write(tag, badges);
        assertEquals(badges, PhotoAdvancementBadges.read(tag));

        CompoundTag empty = new CompoundTag();
        PhotoAdvancementBadges.write(empty, List.of());
        assertFalse(empty.contains(PhotoAdvancementBadges.KEY));
        assertEquals(List.of(), PhotoAdvancementBadges.read(empty));
        assertEquals(List.of(), PhotoAdvancementBadges.read(null));
    }

    @Test
    @DisplayName("reading skips entries without an id and stops at the cap")
    void readIsDefensive() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(new CompoundTag()); // no id
        for (int i = 0; i < 25; i++) {
            CompoundTag t = new CompoundTag();
            t.putString("id", "dungeontrain:enchiridion/a" + i);
            list.add(t);
        }
        tag.put(PhotoAdvancementBadges.KEY, list);
        List<Badge> read = PhotoAdvancementBadges.read(tag);
        assertEquals(PhotoAdvancementBadges.MAX, read.size());
        assertTrue(read.get(0).id().endsWith("/a0"));
        assertEquals("", read.get(0).icon());
    }
}
