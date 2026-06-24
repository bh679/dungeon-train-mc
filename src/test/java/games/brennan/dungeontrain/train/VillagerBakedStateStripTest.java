package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.event.VillagerTrainSpawnEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link CarriageContentsPlacer#stripBakedVillagerState}: train villagers must
 * spawn with a <b>fresh name + freshly-rolled trades</b>, not the snapshot the in-game
 * editor baked into the carriage template. The editor captures live villager NBT, so a
 * carriage saved with a named, traded villager would otherwise re-spawn that exact
 * villager forever (the duplicate-villager bug). The strip removes the dynamic fields so
 * {@code NameComposer} and {@link VillagerTrainSpawnEvents} re-derive them per spawn.
 */
final class VillagerBakedStateStripTest {

    private static CompoundTag villagerWithBakedState() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:villager");
        nbt.putString("CustomName", "{\"text\":\"Bob\"}");
        nbt.putInt("Xp", 5);

        CompoundTag offers = new CompoundTag();
        offers.put("Recipes", new ListTag());
        nbt.put("Offers", offers);

        // Authored intent we must preserve.
        CompoundTag villagerData = new CompoundTag();
        villagerData.putString("profession", "minecraft:armorer");
        villagerData.putInt("level", 3);
        nbt.put("VillagerData", villagerData);

        return nbt;
    }

    private static ListTag tags(String... values) {
        ListTag list = new ListTag();
        for (String v : values) list.add(StringTag.valueOf(v));
        return list;
    }

    @Test
    void stripsBakedNameTradesAndRerollMarker() {
        CompoundTag nbt = villagerWithBakedState();
        nbt.put("Tags", tags(VillagerTrainSpawnEvents.REROLLED_TAG, "some_other_tag"));

        CarriageContentsPlacer.stripBakedVillagerState(nbt);

        assertFalse(nbt.contains("CustomName"), "CustomName must be stripped for a fresh AIN name");
        assertFalse(nbt.contains("Offers"), "Offers must be stripped so trades reroll");
        assertFalse(nbt.contains("Xp"), "trade Xp must be reset");

        // The reroll marker is gone, but unrelated tags survive.
        ListTag remaining = nbt.getList("Tags", Tag.TAG_STRING);
        assertEquals(1, remaining.size(), "only the reroll marker should be removed");
        assertEquals("some_other_tag", remaining.getString(0));

        // Authored profession is preserved.
        assertTrue(nbt.contains("VillagerData"), "VillagerData (authored profession) must be kept");
        assertEquals("minecraft:armorer", nbt.getCompound("VillagerData").getString("profession"));
    }

    @Test
    void removesTagsListEntirelyWhenOnlyMarkerPresent() {
        CompoundTag nbt = villagerWithBakedState();
        nbt.put("Tags", tags(VillagerTrainSpawnEvents.REROLLED_TAG));

        CarriageContentsPlacer.stripBakedVillagerState(nbt);

        assertFalse(nbt.contains("Tags"), "an emptied Tags list should be removed");
    }

    @Test
    void leavesNonVillagerEntitiesUntouched() {
        CompoundTag nbt = villagerWithBakedState();
        nbt.putString("id", "minecraft:zombie");
        nbt.put("Tags", tags(VillagerTrainSpawnEvents.REROLLED_TAG));

        CarriageContentsPlacer.stripBakedVillagerState(nbt);

        assertTrue(nbt.contains("CustomName"), "guard must no-op on non-villagers");
        assertTrue(nbt.contains("Offers"));
        assertTrue(nbt.contains("Xp"));
        assertEquals(1, nbt.getList("Tags", Tag.TAG_STRING).size());
    }
}
