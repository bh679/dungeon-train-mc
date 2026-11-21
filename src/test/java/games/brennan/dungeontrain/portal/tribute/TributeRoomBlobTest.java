package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomBlobTest {

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
        TributeRoomBlob blob = new TributeRoomBlob("abc", "Brennan", snapshot(11, 7, 13), 3, 1234L, "normal");
        TributeRoomBlob back = TributeRoomBlob.decode(blob.encode());
        assertEquals(blob.owner(), back.owner());
        assertEquals(blob.ownerName(), back.ownerName());
        assertEquals(blob.snapshot(), back.snapshot());
        assertEquals(blob.revision(), back.revision());
        assertEquals(blob.savedAtMs(), back.savedAtMs());
        assertEquals(blob.difficulty(), back.difficulty());
        assertNull(back.report());
    }

    @Test
    void initialIsDefaultAndSavedIsNot() {
        TributeRoomBlob initial = TributeRoomBlob.initial("abc", "Brennan", snapshot(11, 7, 13));
        assertTrue(initial.isDefault());
        TributeRoomBlob saved = initial.saved(snapshot(11, 7, 13), "Brennan", 99L, "hard");
        assertFalse(saved.isDefault());
        assertEquals(1, saved.revision());
        assertEquals("abc", saved.owner());
        assertEquals("hard", saved.difficulty());
    }

    @Test
    void matchesOnlyItsOwnBox() {
        TributeRoomBlob blob = TributeRoomBlob.initial("abc", "", snapshot(11, 7, 13));
        assertTrue(blob.matches(new net.minecraft.core.Vec3i(11, 7, 13)));
        assertFalse(blob.matches(new net.minecraft.core.Vec3i(11, 7, 11)));
    }

    @Test
    void decodeRefusesForeignTags() {
        assertNull(TributeRoomBlob.decode(null));
        assertNull(TributeRoomBlob.decode(new CompoundTag()));
    }

    @Test
    void toPortalRoomBlobCarriesOwnerAndNoLease() {
        TributeRoomBlob blob = TributeRoomBlob.initial("abc", "", snapshot(11, 7, 13));
        assertEquals("abc", blob.toPortalRoomBlob().owner());
        assertFalse(blob.toPortalRoomBlob().isLeased());
    }

    @Test
    void reportRidesWithTheRoomAndSurvivesASave() {
        TributeRoomBlob blob = TributeRoomBlob.initial("abc", "Brennan", snapshot(11, 7, 13))
            .withReport(new byte[] {1, 2, 3}, "Brennan fell from a high place", 55L);
        assertTrue(blob.hasReport());
        TributeRoomBlob back = TributeRoomBlob.decode(blob.encode());
        assertArrayEquals(new byte[] {1, 2, 3}, back.report());
        assertEquals("Brennan fell from a high place", back.reportMessage());
        assertEquals(55L, back.reportAtMs());
        TributeRoomBlob saved = back.saved(snapshot(11, 7, 13), "Brennan", 99L, "hard");
        assertTrue(saved.hasReport(), "a save keeps the last death");
        assertFalse(TributeRoomBlob.initial("abc", "", snapshot(11, 7, 13)).hasReport());
    }
}
