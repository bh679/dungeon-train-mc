package games.brennan.dungeontrain.portal.home;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomeRoomBlobTest {

    private static CompoundTag snapshot(int l, int h, int w) {
        CompoundTag snap = new CompoundTag();
        snap.putInt("v", 2);
        snap.putInt("l", l);
        snap.putInt("h", h);
        snap.putInt("w", w);
        snap.put("cells", new ListTag());
        return snap;
    }

    @Test
    void roundTripsThroughNbt() {
        HomeRoomBlob blob = new HomeRoomBlob("abc", "Brennan", snapshot(11, 7, 13), 3, 1234L, "normal");
        HomeRoomBlob back = HomeRoomBlob.decode(blob.encode());
        assertEquals(blob, back);
    }

    @Test
    void initialIsDefaultAndSavedIsNot() {
        HomeRoomBlob initial = HomeRoomBlob.initial("abc", "Brennan", snapshot(11, 7, 13));
        assertTrue(initial.isDefault());
        HomeRoomBlob saved = initial.saved(snapshot(11, 7, 13), "Brennan", 99L, "hard");
        assertFalse(saved.isDefault());
        assertEquals(1, saved.revision());
        assertEquals("abc", saved.owner());
        assertEquals("hard", saved.difficulty());
    }

    @Test
    void matchesOnlyItsOwnBox() {
        HomeRoomBlob blob = HomeRoomBlob.initial("abc", "", snapshot(11, 7, 13));
        assertTrue(blob.matches(new net.minecraft.core.Vec3i(11, 7, 13)));
        assertFalse(blob.matches(new net.minecraft.core.Vec3i(11, 7, 11)));
    }

    @Test
    void decodeRefusesForeignTags() {
        assertNull(HomeRoomBlob.decode(null));
        assertNull(HomeRoomBlob.decode(new CompoundTag()));
    }

    @Test
    void toPortalRoomBlobCarriesOwnerAndNoLease() {
        HomeRoomBlob blob = HomeRoomBlob.initial("abc", "", snapshot(11, 7, 13));
        assertEquals("abc", blob.toPortalRoomBlob().owner());
        assertFalse(blob.toPortalRoomBlob().isLeased());
    }
}
