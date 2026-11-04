package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/**
 * The wire codec and backpack order of {@link NoteAuthorGear} — the parts that decide whether the
 * author's death gear survives the trip through the relay intact. Item (de)serialisation itself is
 * vanilla's and is exercised in-game.
 */
class NoteAuthorGearTest {

    @Test
    void encodeDecodeRoundTripsTheGearTag() {
        CompoundTag equipment = new CompoundTag();
        equipment.putString("head", "minecraft:diamond_helmet");
        ListTag pack = new ListTag();
        pack.add(StringTag.valueOf("minecraft:ender_pearl"));
        CompoundTag gear = new CompoundTag();
        gear.put(NoteAuthorGear.KEY_EQUIPMENT, equipment);
        gear.put(NoteAuthorGear.KEY_PACK, pack);

        String wire = NoteAuthorGear.encode(gear);
        assertTrue(!wire.isEmpty() && wire.length() <= NoteAuthorGear.MAX_ENCODED_CHARS);
        assertEquals(gear, NoteAuthorGear.decode(wire));
    }

    @Test
    void anEmptyLoadoutStillTravels() {
        // An author who died with nothing sends an echo with nothing — that is an empty tag, not
        // "no gear captured" (which would fall back to difficulty gear).
        CompoundTag gear = new CompoundTag();
        gear.put(NoteAuthorGear.KEY_EQUIPMENT, new CompoundTag());
        gear.put(NoteAuthorGear.KEY_PACK, new ListTag());
        CompoundTag back = NoteAuthorGear.decode(NoteAuthorGear.encode(gear));
        assertNotNull(back);
        assertEquals(gear, back);
    }

    @Test
    void oversizeGearIsDroppedRatherThanSent() {
        // Incompressible filler: a full shulker of written books must never block the note.
        Random rng = new Random(42);
        // Spread over several tags: a single NBT string is capped at 64 KB of UTF and written empty past it.
        CompoundTag gear = new CompoundTag();
        for (int t = 0; t < 12; t++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10_000; i++) sb.append((char) ('!' + rng.nextInt(90)));
            gear.putString("filler" + t, sb.toString());
        }
        assertEquals("", NoteAuthorGear.encode(gear));
    }

    @Test
    void blankCorruptOrOversizeStringsDecodeToNull() {
        assertNull(NoteAuthorGear.decode(null));
        assertNull(NoteAuthorGear.decode(""));
        assertNull(NoteAuthorGear.decode("   "));
        assertNull(NoteAuthorGear.decode("not base64 !!"));
        assertNull(NoteAuthorGear.decode("aGVsbG8gd29ybGQ=")); // valid base64, not gzip NBT
        assertNull(NoteAuthorGear.decode("A".repeat(NoteAuthorGear.MAX_ENCODED_CHARS + 4)));
    }

    @Test
    void packPutsFoodFirstKeepsOrderAndCaps() {
        List<String> inv = List.of("pearl", "bread", "wheat", "apple", "head", "potion",
                "emerald", "book", "steak", "sword");
        List<String> pack = NoteAuthorGear.packOrder(inv, s -> s.equals("bread") || s.equals("apple")
                || s.equals("steak"), NoteAuthorGear.PACK_SLOTS);
        assertEquals(List.of("bread", "apple", "steak", "pearl", "wheat", "head", "potion", "emerald"), pack);
    }

    @Test
    void packOfFewerThanTheCapKeepsEverything() {
        assertEquals(List.of("pearl"), NoteAuthorGear.packOrder(List.of("pearl"), s -> false, 8));
        assertEquals(List.of(), NoteAuthorGear.packOrder(List.<String>of(), s -> true, 8));
    }
}
