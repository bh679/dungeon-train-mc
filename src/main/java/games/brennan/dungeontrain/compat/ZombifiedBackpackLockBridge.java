package games.brennan.dungeontrain.compat;

import games.brennan.dungeontotems.curse.ZombiePack;
import games.brennan.ediblebackpacks.EdibleBackpacksApi;
import games.brennan.ediblebackpacks.menu.BackpackSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;

/**
 * A Zombified player can't reach their backpack, just as Dungeon Totems locks their main inventory.
 * Both siblings are bundled with Dungeon Train and know nothing of each other, so this wires them:
 * <ul>
 *   <li>Edible Backpacks' access lock follows {@link ZombiePack#isLocked}: backpack slots take nothing in,
 *       give nothing out, and pickups skip the backpack (Dungeon Totems then sends them to the hotbar).</li>
 *   <li>Dungeon Totems counts {@link BackpackSlot}s as part of the pack, so they grey out with it.</li>
 *   <li>The exception follows {@link ZombiePack#reachable}: a Weakness potion or Totem of Weakness can still be
 *       taken out of the backpack, as out of the main inventory.</li>
 * </ul>
 * The caller ({@code DungeonTrain.commonSetup}) gates on both mods being loaded and catches
 * {@link Throwable}, so a sibling build without either seam leaves the backpack usable.
 */
public final class ZombifiedBackpackLockBridge {

    private ZombifiedBackpackLockBridge() {}

    public static void install() {
        EdibleBackpacksApi.setAccessLock(ZombiePack::isLocked);
        ZombiePack.addPackSlots(ZombifiedBackpackLockBridge::backpackOwner);
    }

    /** The Weakness exception, apart from {@link #install} so an older sibling without it keeps the lock. */
    public static void installWeaknessException() {
        EdibleBackpacksApi.setTakeOutException((player, stack) -> ZombiePack.reachable(stack));
    }

    private static Player backpackOwner(Slot slot) {
        return slot instanceof BackpackSlot backpack ? backpack.owner() : null;
    }
}
