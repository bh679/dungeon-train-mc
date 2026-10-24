package games.brennan.dungeontrain.perks;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the three guarantees sibling perk sessions code against: rank is 0 unless unlocked AND
 * holding points; death clears points but never the unlocked set; the codec round-trips and
 * tolerates missing fields.
 */
final class PerkStateTest {

    @Test
    @DisplayName("rank is 0 when locked, 0 when unlocked without points, points when both")
    void rankNeedsUnlockAndPoints() {
        PerkState s = PerkState.empty();
        assertEquals(0, s.rank("health"));

        PerkState pointsOnly = s.withActive("health", 3);
        assertEquals(3, pointsOnly.activePoints("health"));
        assertEquals(0, pointsOnly.rank("health"), "points without unlock give no rank");

        PerkState unlockedOnly = s.withUnlocked("health");
        assertTrue(unlockedOnly.isUnlocked("health"));
        assertEquals(0, unlockedOnly.rank("health"));

        PerkState both = pointsOnly.withUnlocked("health");
        assertEquals(3, both.rank("health"));
    }

    @Test
    @DisplayName("clearedActive drops every point and keeps the unlocked set")
    void deathKeepsUnlocked() {
        PerkState s = PerkState.empty().withUnlocked("health").withUnlocked("speed")
            .withActive("health", 4).withActive("speed", 1);
        PerkState dead = s.clearedActive();
        assertTrue(dead.isUnlocked("health"));
        assertTrue(dead.isUnlocked("speed"));
        assertEquals(0, dead.rank("health"));
        assertEquals(0, dead.rank("speed"));
        assertTrue(dead.active().isEmpty());
        assertNotSame(s, dead);
        assertSame(dead, dead.clearedActive(), "no-op returns the same instance");
    }

    @Test
    @DisplayName("mutators never touch the original")
    void immutable() {
        PerkState a = PerkState.empty().withUnlocked("health");
        PerkState b = a.withActive("health", 2);
        assertEquals(0, a.rank("health"));
        assertEquals(2, b.rank("health"));
        PerkState c = b.withActive("health", 0);
        assertEquals(2, b.rank("health"));
        assertEquals(0, c.activePoints("health"));
        assertFalse(c.active().containsKey("health"), "zero removes the entry");
    }

    @Test
    @DisplayName("withoutUnlocked also drops that perk's points")
    void relockDropsPoints() {
        PerkState s = PerkState.empty().withUnlocked("health").withActive("health", 5).withoutUnlocked("health");
        assertFalse(s.isUnlocked("health"));
        assertEquals(0, s.activePoints("health"));
    }

    @Test
    @DisplayName("codec round-trips unlocked + active")
    void codecRoundTrip() {
        PerkState s = PerkState.empty().withUnlocked("health").withUnlocked("drift-chance")
            .withActive("health", 7).withActive("speed", 2);
        DataResult<Tag> enc = PerkState.CODEC.encodeStart(NbtOps.INSTANCE, s);
        Tag tag = enc.getOrThrow();
        PerkState back = PerkState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(s, back);
        assertEquals(7, back.rank("health"));
        assertEquals(0, back.rank("speed"), "speed has points but is locked");
        assertEquals(2, back.activePoints("speed"));
    }

    @Test
    @DisplayName("codec tolerates an empty compound and a partial one")
    void codecMissingFields() {
        PerkState empty = PerkState.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        assertTrue(empty.isEmpty());

        CompoundTag partial = new CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(net.minecraft.nbt.StringTag.valueOf("health"));
        partial.put("unlocked", list);
        PerkState s = PerkState.CODEC.parse(NbtOps.INSTANCE, partial).getOrThrow();
        assertTrue(s.isUnlocked("health"));
        assertEquals(0, s.rank("health"));
    }

    @Test
    @DisplayName("stream codec round-trips")
    void streamCodecRoundTrip() {
        PerkState s = PerkState.empty().withUnlocked("health").withActive("health", 9).withActive("damage", 1);
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        PerkState.STREAM_CODEC.encode(buf, s);
        PerkState back = PerkState.STREAM_CODEC.decode(buf);
        assertEquals(s, back);
    }
}
