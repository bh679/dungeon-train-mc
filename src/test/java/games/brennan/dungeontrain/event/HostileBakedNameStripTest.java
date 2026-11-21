package games.brennan.dungeontrain.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link HostileTrainNameEvents#stripBakedHostileName}: a hostile mob AIN named in the
 * editor must not stamp its name, or its "already rolled" marker, into every carriage, while a
 * name the template author set by hand stays.
 */
final class HostileBakedNameStripTest {

    private static CompoundTag zombie(String... tags) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:zombie");
        nbt.putString("CustomName", "{\"text\":\"Grimbold\"}");
        nbt.putBoolean("CustomNameVisible", true);
        ListTag list = new ListTag();
        for (String t : tags) list.add(StringTag.valueOf(t));
        if (!list.isEmpty()) nbt.put("Tags", list);
        return nbt;
    }

    @Test
    void ainRolledNameIsStripped() {
        CompoundTag nbt = zombie(HostileTrainNameEvents.NAME_ROLLED_TAG);
        HostileTrainNameEvents.stripBakedHostileName(nbt);
        assertFalse(nbt.contains("CustomName"));
        assertFalse(nbt.contains("CustomNameVisible"));
        assertFalse(nbt.contains("Tags"), "an emptied Tags list is removed");
    }

    @Test
    void otherTagsSurvive() {
        CompoundTag nbt = zombie("keep_me", HostileTrainNameEvents.NAME_ROLLED_TAG);
        HostileTrainNameEvents.stripBakedHostileName(nbt);
        ListTag tags = nbt.getList("Tags", Tag.TAG_STRING);
        assertEquals(1, tags.size());
        assertEquals("keep_me", tags.getString(0));
    }

    @Test
    void handSetNameIsKept() {
        CompoundTag nbt = zombie("keep_me");
        HostileTrainNameEvents.stripBakedHostileName(nbt);
        assertTrue(nbt.contains("CustomName"));
        assertTrue(nbt.getBoolean("CustomNameVisible"));
    }

    @Test
    void untaggedNameIsKept() {
        CompoundTag nbt = zombie();
        HostileTrainNameEvents.stripBakedHostileName(nbt);
        assertTrue(nbt.contains("CustomName"));
    }

    @Test
    void markerMatchesAin() {
        // Must equal AIN's NameComposer.HOSTILE_NAME_ROLLED_TAG (not referenced, so DT builds
        // against AIN releases without it).
        assertEquals("adventureitemnames_name_rolled", HostileTrainNameEvents.NAME_ROLLED_TAG);
    }
}
