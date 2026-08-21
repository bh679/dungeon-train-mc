package games.brennan.dungeontrain.train;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The decor fingerprint — the value that decides whether a shared carriage's entities have been EDITED
 * (an armor stand placed, a painting moved) as opposed to simply having wandered. Getting this wrong is
 * expensive in both directions: too sensitive and every sweep re-uploads the build, too blunt and a
 * builder's item frame never reaches the pool at all.
 *
 * <p>Capture and spawn themselves need a live server level and a Sable sub-level, so they are exercised
 * in-game rather than here; these are the pure parts.</p>
 */
class CarriageEntitySnapshotTest {

    private static CompoundTag ent(String id, double x, double y, double z, String name) {
        CompoundTag e = new CompoundTag();
        e.putString("id", id);
        ListTag p = new ListTag();
        p.add(DoubleTag.valueOf(x));
        p.add(DoubleTag.valueOf(y));
        p.add(DoubleTag.valueOf(z));
        e.put("p", p);
        if (name != null) e.putString("dn", name);
        return e;
    }

    private static ListTag ents(CompoundTag... entries) {
        ListTag list = new ListTag();
        for (CompoundTag e : entries) list.add(e);
        return list;
    }

    @Test
    void anEmptyOrNullListIsTheSameFingerprint() {
        assertEquals(CarriageEntitySnapshot.decorFingerprint(null),
            CarriageEntitySnapshot.decorFingerprint(new ListTag()));
    }

    @Test
    void placingDecorChangesTheFingerprint() {
        ListTag before = ents(ent("minecraft:armor_stand", 1.5, 0, 1.5, null));
        ListTag after = ents(
            ent("minecraft:armor_stand", 1.5, 0, 1.5, null),
            ent("minecraft:item_frame", 3.5, 2.5, 0.06, null));

        assertNotEquals(CarriageEntitySnapshot.decorFingerprint(before),
            CarriageEntitySnapshot.decorFingerprint(after));
    }

    @Test
    void movingOrRenamingDecorChangesTheFingerprint() {
        ListTag at = ents(ent("minecraft:painting", 2.5, 2, 0.5, null));
        ListTag moved = ents(ent("minecraft:painting", 4.5, 2, 0.5, null));
        ListTag named = ents(ent("minecraft:armor_stand", 2.5, 2, 0.5, "Conductor"));
        ListTag renamed = ents(ent("minecraft:armor_stand", 2.5, 2, 0.5, "Driver"));

        assertNotEquals(CarriageEntitySnapshot.decorFingerprint(at),
            CarriageEntitySnapshot.decorFingerprint(moved));
        assertNotEquals(CarriageEntitySnapshot.decorFingerprint(named),
            CarriageEntitySnapshot.decorFingerprint(renamed));
    }

    @Test
    void aMobWanderingDoesNotChangeTheFingerprint() {
        // The whole point: a villager pacing the carriage must not re-upload the build every 30 seconds.
        ListTag here = ents(
            ent("minecraft:armor_stand", 1.5, 0, 1.5, null),
            ent("minecraft:villager", 2.0, 0, 2.0, null));
        ListTag there = ents(
            ent("minecraft:armor_stand", 1.5, 0, 1.5, null),
            ent("minecraft:villager", 5.4, 0, 3.1, null));

        assertEquals(CarriageEntitySnapshot.decorFingerprint(here),
            CarriageEntitySnapshot.decorFingerprint(there));
    }

    @Test
    void theOrderEntitiesAreListedInDoesNotMatter() {
        // The live scan and a captured list enumerate the same carriage in different orders; an
        // order-sensitive fingerprint would report an edit on every sweep.
        CompoundTag stand = ent("minecraft:armor_stand", 1.5, 0, 1.5, null);
        CompoundTag frame = ent("minecraft:item_frame", 3.5, 2.5, 0.06, null);

        assertEquals(CarriageEntitySnapshot.decorFingerprint(ents(stand, frame)),
            CarriageEntitySnapshot.decorFingerprint(ents(frame, stand)));
    }

    @Test
    void driftWithinAThirdOfABlockIsIgnoredButADeliberateNudgeIsNot() {
        // Positions are quantised to thirds of a block: drift inside one bucket reads as "unchanged",
        // a real reposition crosses buckets. (A value sitting exactly on a bucket edge can of course
        // fall either side — decor entities don't drift at all, so nothing rides on that case.)
        ListTag at = ents(ent("minecraft:armor_stand", 1.6, 0, 1.6, null));
        ListTag jittered = ents(ent("minecraft:armor_stand", 1.62, 0, 1.58, null));
        ListTag nudged = ents(ent("minecraft:armor_stand", 2.6, 0, 1.6, null));

        assertEquals(CarriageEntitySnapshot.decorFingerprint(at),
            CarriageEntitySnapshot.decorFingerprint(jittered));
        assertNotEquals(CarriageEntitySnapshot.decorFingerprint(at),
            CarriageEntitySnapshot.decorFingerprint(nudged));
    }

    @Test
    void idsOfListsEveryCapturedEntityInOrder() {
        assertEquals(List.of("minecraft:armor_stand", "minecraft:villager"),
            CarriageEntitySnapshot.idsOf(ents(
                ent("minecraft:armor_stand", 0, 0, 0, null),
                ent("minecraft:villager", 1, 0, 1, null))));
        assertEquals(List.of(), CarriageEntitySnapshot.idsOf(null));
    }
}
