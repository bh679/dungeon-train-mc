package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.LeasePhoto;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.component.StackedPhotographs;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.AlbumPage;
import io.github.mortuusars.exposure.world.item.component.album.SignedAlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.SignedAlbumPage;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a drifting build names the photos it holds, and how a leasing world adopts them. */
final class CarriagePhotosTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A print the relay knows as {@code id}. Paper stands in for Exposure's item: only the component matters. */
    private static ItemStack photo(int id) {
        Frame frame = Frame.EMPTY.toMutable().updateExtraData(e -> e.putInt(SharedPhotos.SHARED_ID_KEY, id)).toImmutable();
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
        return stack;
    }

    private static ItemStack holding(net.minecraft.world.item.Item item, ItemStack... inside) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(inside)));
        return stack;
    }

    /** Refs off a live stack with NO registries: any serialisation of the stack would throw. */
    private static Set<CarriagePhotos.Ref> refs(ItemStack stack) {
        return CarriagePhotos.refsIn(stack, null);
    }

    private static Set<CarriagePhotos.Ref> ids(int... ids) {
        Set<CarriagePhotos.Ref> out = new java.util.LinkedHashSet<>();
        for (int id : ids) out.add(new CarriagePhotos.Ref(id, ""));
        return out;
    }

    @Test
    @DisplayName("ordinary items hold no photos and are never serialised to find out")
    void ordinaryItems() {
        assertEquals(Set.of(), refs(new ItemStack(Items.STONE, 64)));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Sting"));
        sword.setDamageValue(12);
        assertEquals(Set.of(), refs(sword));
        assertEquals(Set.of(), refs(holding(Items.SHULKER_BOX, new ItemStack(Items.BREAD))));
        assertEquals(Set.of(), refs(ItemStack.EMPTY));
    }

    @Test
    @DisplayName("a loose print is listed, and a print without a relay name is not")
    void loosePrint() {
        assertEquals(ids(7), refs(photo(7)));
        assertEquals(Set.of(), refs(photo(0)));
    }

    @Test
    @DisplayName("prints inside a stack of prints, an album and a signed album are all listed")
    void exposureHolders() {
        ItemStack stacked = new ItemStack(Items.PAPER);
        stacked.set(Exposure.DataComponents.STACKED_PHOTOGRAPHS, new StackedPhotographs(List.of(photo(1), photo(2))));
        assertEquals(ids(1, 2), refs(stacked));

        ItemStack album = new ItemStack(Items.BOOK);
        album.set(Exposure.DataComponents.ALBUM_CONTENT, new AlbumContent(List.of(
                new AlbumPage(photo(3), ""), new AlbumPage(ItemStack.EMPTY, "blank"), new AlbumPage(photo(4), "x"))));
        assertEquals(ids(3, 4), refs(album));

        ItemStack signed = new ItemStack(Items.BOOK);
        signed.set(Exposure.DataComponents.SIGNED_ALBUM_CONTENT, new SignedAlbumContent("t", "a", List.of(
                new SignedAlbumPage(photo(5), Component.empty()))));
        assertEquals(ids(5), refs(signed));
    }

    @Test
    @DisplayName("prints nested in boxes and bundles are listed, at any reasonable depth")
    void nestedContainers() {
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(photo(9), new ItemStack(Items.APPLE))));
        ItemStack album = new ItemStack(Items.BOOK);
        album.set(Exposure.DataComponents.ALBUM_CONTENT, new AlbumContent(List.of(new AlbumPage(photo(10), ""))));
        ItemStack box = holding(Items.SHULKER_BOX, photo(8), bundle, album, new ItemStack(Items.STONE));
        assertEquals(ids(8, 9, 10), refs(box));
        ItemStack outer = new ItemStack(Items.BUNDLE);
        outer.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(box)));
        assertEquals(ids(8, 9, 10), refs(outer));
    }

    @Test
    @DisplayName("only a saved item naming a photograph frame is worth parsing")
    void photoTags() {
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_name", new CompoundTag());
        CompoundTag sword = new CompoundTag();
        sword.putString("id", "minecraft:diamond_sword");
        sword.putInt("count", 1);
        sword.put("components", components);
        assertFalse(CarriagePhotos.isPhotoTag(sword));
        CompoundTag photoComponents = new CompoundTag();
        photoComponents.put(CarriagePhotos.PHOTOGRAPH_FRAME_KEY, new CompoundTag());
        CompoundTag photo = sword.copy();
        photo.put("components", photoComponents);
        assertTrue(CarriagePhotos.isPhotoTag(photo));
        assertEquals("exposure:photograph_frame", CarriagePhotos.PHOTOGRAPH_FRAME_KEY);
    }

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
