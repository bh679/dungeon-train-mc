package games.brennan.dungeontrain.difficulty;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection half of {@link BakedItemStats} — deciding whether a saved template's block-entity NBT
 * holds gear with impossible stats.
 *
 * <p>Only the detection is exercised here. The repair calls into AIS and needs a live
 * {@code ServerLevel} for its position-derived tier, so it is verified in-game against the reported
 * case (the {@code evilhouse} room's chest) rather than mocked into meaninglessness here.</p>
 *
 * <p>The numbers are the real ones from that chest.</p>
 */
final class BakedItemStatsTest {

    /** The chestplate from the bug report. */
    private static final double REPORTED = 4_433_488.289050015;

    private static CompoundTag item(String id, double... amounts) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id);
        ListTag modifiers = new ListTag();
        for (double a : amounts) {
            CompoundTag m = new CompoundTag();
            m.putDouble("amount", a);
            m.putString("type", "minecraft:generic.armor");
            modifiers.add(m);
        }
        CompoundTag attributes = new CompoundTag();
        attributes.put("modifiers", modifiers);
        CompoundTag components = new CompoundTag();
        components.put("minecraft:attribute_modifiers", attributes);
        stack.put("components", components);
        return stack;
    }

    private static CompoundTag chestHolding(CompoundTag... items) {
        ListTag list = new ListTag();
        for (CompoundTag i : items) list.add(i);
        CompoundTag be = new CompoundTag();
        be.putString("id", "minecraft:chest");
        be.put("Items", list);
        return be;
    }

    @Test
    @DisplayName("the reported chestplate is detected; its sane toughness alone is not")
    void reportedItem_isCorrupt() {
        assertTrue(BakedItemStats.isAbsurd(REPORTED));
        // The same item's second modifier — an ordinary roll that must not trip anything.
        assertFalse(BakedItemStats.isAbsurd(1.26));
        assertTrue(BakedItemStats.isCorrupt(item("minecraft:diamond_chestplate", REPORTED, 1.26)));
    }

    @Test
    @DisplayName("ordinary gear is left alone, however deep the run")
    void ordinaryGear_isNotCorrupt() {
        // 0.15 * TemplateGate.MAX_LEVEL is the steepest the game can legitimately reach.
        for (double amount : new double[] {0.0, 1.26, 8.0, 20.0, 150.0, 999.0, -3.28}) {
            assertFalse(BakedItemStats.isAbsurd(amount), "must accept a real roll: " + amount);
            assertFalse(BakedItemStats.isCorrupt(item("minecraft:diamond_chestplate", amount)));
        }
    }

    @Test
    @DisplayName("a non-finite amount counts as impossible")
    void nonFinite_isAbsurd() {
        assertTrue(BakedItemStats.isAbsurd(Double.NaN));
        assertTrue(BakedItemStats.isAbsurd(Double.POSITIVE_INFINITY));
        assertTrue(BakedItemStats.isAbsurd(Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("tags that are not stacks, or carry no stats, are not corrupt")
    void nonStacks_areNotCorrupt() {
        assertFalse(BakedItemStats.isCorrupt(new CompoundTag()));
        CompoundTag noComponents = new CompoundTag();
        noComponents.putString("id", "minecraft:stone");
        assertFalse(BakedItemStats.isCorrupt(noComponents));
        assertFalse(BakedItemStats.containsCorrupt(null));
        assertFalse(BakedItemStats.containsCorrupt(chestHolding(item("minecraft:stick", 1.0))));
    }

    @Test
    @DisplayName("one bad stack anywhere in a container is found")
    void containerWalk_findsNestedCorruption() {
        // The reported chest's shape: mostly ordinary items, one ruinous one.
        assertTrue(BakedItemStats.containsCorrupt(chestHolding(
            item("minecraft:golden_helmet", 2.0),
            item("minecraft:nether_wart"),
            item("minecraft:diamond_chestplate", REPORTED, 1.26))));
        assertFalse(BakedItemStats.containsCorrupt(chestHolding(
            item("minecraft:golden_helmet", 2.0),
            item("minecraft:diamond_chestplate", 8.0, 1.26))));
    }

    @Test
    @DisplayName("baked entity equipment is covered by the same walk as a chest")
    void entityEquipment_isFound() {
        // An armour stand captured into a contents template carries its gear under ArmorItems —
        // a different shape from a container, which is why the walk is generic rather than a list
        // of the container keys somebody remembered.
        ListTag armor = new ListTag();
        armor.add(item("minecraft:diamond_chestplate", REPORTED));
        CompoundTag stand = new CompoundTag();
        stand.putString("id", "minecraft:armor_stand");
        stand.put("ArmorItems", armor);
        assertTrue(BakedItemStats.containsCorrupt(stand));
    }
}
