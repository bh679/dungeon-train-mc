package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.LeasePhoto;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a drifting build names the photos it holds, and how a leasing world adopts them. */
final class CarriagePhotosTest {

    private static CompoundTag extra(int sharedId, String uploadKey) {
        CompoundTag tag = new CompoundTag();
        if (sharedId > 0) tag.putInt(SharedPhotos.SHARED_ID_KEY, sharedId);
        if (uploadKey != null) tag.putString(SharedPhotos.UPLOAD_KEY, uploadKey);
        return tag;
    }

    @Test
    @DisplayName("a found photo is named by its relay id, an own print by its upload key, a Free Play print not at all")
    void refs() {
        assertEquals(Optional.of(new CarriagePhotos.Ref(42, "")), CarriagePhotos.refOf(extra(42, null)));
        assertEquals(Optional.of(new CarriagePhotos.Ref(42, "")), CarriagePhotos.refOf(extra(42, "k1")));
        assertEquals(Optional.of(new CarriagePhotos.Ref(0, "k1")), CarriagePhotos.refOf(extra(0, "k1")));
        assertEquals(Optional.empty(), CarriagePhotos.refOf(extra(0, null)));
        assertEquals(Optional.empty(), CarriagePhotos.refOf(extra(0, " ")));
    }

    @Test
    @DisplayName("the upload list carries each photo once, by id or key, and never more than the cap")
    void uploadJson() {
        JsonArray json = CarriagePhotos.toJson(List.of(
                new CarriagePhotos.Ref(7, ""), new CarriagePhotos.Ref(0, "abc"), new CarriagePhotos.Ref(7, "")));
        assertEquals(2, json.size());
        assertEquals(7, json.get(0).getAsJsonObject().get("id").getAsInt());
        assertFalse(json.get(0).getAsJsonObject().has("key"));
        assertEquals("abc", json.get(1).getAsJsonObject().get("key").getAsString());

        List<CarriagePhotos.Ref> many = IntStream.rangeClosed(1, CarriagePhotos.MAX_REFS + 10)
                .mapToObj(i -> new CarriagePhotos.Ref(i, "")).toList();
        assertEquals(CarriagePhotos.MAX_REFS, CarriagePhotos.toJson(many).size());
    }

    @Test
    @DisplayName("a photo matches the relay's entry by id, or an own print by its key")
    void matching() {
        List<LeasePhoto> listed = List.of(
                new LeasePhoto(5, "", "Alex", 2, 1, 3, 0),
                new LeasePhoto(9, "k9", "Steve", 0, 3, 3, 0));
        assertEquals(5, CarriagePhotos.match(new CarriagePhotos.Ref(5, ""), listed).orElseThrow().id());
        assertEquals(9, CarriagePhotos.match(new CarriagePhotos.Ref(0, "k9"), listed).orElseThrow().id());
        assertTrue(CarriagePhotos.match(new CarriagePhotos.Ref(0, "nope"), listed).isEmpty());
        assertTrue(CarriagePhotos.match(new CarriagePhotos.Ref(6, ""), listed).isEmpty());
        assertTrue(CarriagePhotos.match(new CarriagePhotos.Ref(5, ""), null).isEmpty());
    }

    @Test
    @DisplayName("an adopted photo becomes the found photo: relay id, author and counts, and no upload key")
    void adoption() {
        CompoundTag tag = extra(0, "secret-key");
        CarriagePhotos.adoptInto(tag, 9, new LeasePhoto(9, "secret-key", "Steve", 1, 2, 3, 4), "Steve");
        assertEquals(9, tag.getInt(SharedPhotos.SHARED_ID_KEY));
        assertEquals("Steve", tag.getString(SharedPhotos.SHARED_AUTHOR_KEY));
        assertFalse(tag.contains(SharedPhotos.UPLOAD_KEY));
        assertEquals(1, tag.getInt(SharedPhotos.SHARED_VIEWS_LEFT_KEY));
        assertEquals(2, tag.getInt(SharedPhotos.SHARED_VIEWS_KEY));
        assertEquals(3, tag.getInt(SharedPhotos.SHARED_MAX_VIEWS_KEY));
        assertEquals(4, tag.getInt(SharedPhotos.SHARED_TRIBUTES_KEY));
    }

    @Test
    @DisplayName("counts the relay did not send are left out rather than written as -1")
    void adoptionWithoutCounts() {
        CompoundTag tag = new CompoundTag();
        CarriagePhotos.adoptInto(tag, 3, new LeasePhoto(3, "", "", -1, -1, -1, -1), "");
        assertEquals(3, tag.getInt(SharedPhotos.SHARED_ID_KEY));
        assertFalse(tag.contains(SharedPhotos.SHARED_AUTHOR_KEY));
        assertFalse(tag.contains(SharedPhotos.SHARED_VIEWS_LEFT_KEY));
        assertFalse(tag.contains(SharedPhotos.SHARED_TRIBUTES_KEY));
    }

    @Test
    @DisplayName("only item compounds are read as items, never a block entity or an entity")
    void itemTags() {
        CompoundTag item = new CompoundTag();
        item.putString("id", "exposure:photograph");
        item.putInt("count", 1);
        item.put("components", new CompoundTag());
        assertTrue(CarriagePhotos.isItemTag(item));

        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:item_frame");
        entity.put("Item", item);
        assertFalse(CarriagePhotos.isItemTag(entity));

        CompoundTag bare = new CompoundTag();
        bare.putString("id", "minecraft:stone");
        bare.putInt("count", 1);
        assertFalse(CarriagePhotos.isItemTag(bare));
    }
}
