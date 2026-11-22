package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapExposures}: which strings a nap looks up as photos, and that the carried photos travel in the nap file. */
final class NapExposuresTest {

    @Test
    @DisplayName("exposure ids are found anywhere in the player tag and run extras, each once")
    void findsIdsDeepInTags() {
        CompoundTag frame = new CompoundTag();
        frame.putString("id", "Lyraluithin_4244");
        CompoundTag item = new CompoundTag();
        item.putString("id", "exposure:photograph");          // a namespaced item id: not a photo id
        CompoundTag components = new CompoundTag();
        components.put("exposure:frame", frame);
        item.put("components", components);
        ListTag inventory = new ListTag();
        inventory.add(item);
        CompoundTag player = new CompoundTag();
        player.put("Inventory", inventory);

        CompoundTag extras = new CompoundTag();
        ListTag frames = new ListTag();
        frames.add(StringTag.valueOf("Lyraluithin_4244"));       // the same shot, also a camera frame
        frames.add(StringTag.valueOf("Lyraluithin_32163"));
        frames.add(StringTag.valueOf("data/exposures/../evil")); // a path: never looked up
        extras.put("cameraFrames", frames);

        assertEquals(List.of("Lyraluithin_4244", "Lyraluithin_32163"),
            NapExposures.candidateIds(player, extras));
    }

    @Test
    @DisplayName("null tags are skipped")
    void nullTagsSkipped() {
        assertTrue(NapExposures.candidateIds(null, new CompoundTag()).isEmpty());
    }

    @Test
    @DisplayName("Exposure's storage prefix is the one ExposureRepository uses")
    void storagePrefix() {
        assertEquals("exposures/", NapExposures.STORAGE_PREFIX);
    }

    @Test
    @DisplayName("carried photos survive the nap file round trip inside the run extras")
    void carriedPhotosRoundTrip() {
        CompoundTag photo = new CompoundTag();
        photo.putInt("width", 2);
        photo.putByteArray("pixels", new byte[] {1, 2, 3, 4});
        CompoundTag carried = new CompoundTag();
        carried.put("Lyraluithin_4244", photo);
        CompoundTag extras = new CompoundTag();
        extras.put(NapExposures.EXTRAS_KEY, carried);

        NapSnapshot nap = new NapSnapshot(UUID.randomUUID(), 1L, "0", "World 1", new CompoundTag(),
            0, 0, "", 1, 0, false, "hard", "", "", new CompoundTag(), extras, 0);
        NapSnapshot back = NapSnapshot.fromTag(nap.toTag());

        assertEquals(carried, back.runExtras().getCompound(NapExposures.EXTRAS_KEY));
        assertEquals(carried, back.boundTo("World 2").withX(5).runExtras().getCompound(NapExposures.EXTRAS_KEY));
    }
}
