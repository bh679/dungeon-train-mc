package games.brennan.dungeontrain.compat.vista;

import games.brennan.dungeontrain.compat.EchoIdentity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One train PlayerMob in {@link #ONE_IN} spawns wearing a Vista TV for a hat (switched off). Echoes
 * never do: they wear what their fallen player's life gave them.
 *
 * <p>Runs after the difficulty gear, so the TV takes the head slot over any rolled helmet. The slot's
 * drop chance is reset to the vanilla default, so the TV is a rare drop rather than a guaranteed one.</p>
 */
public final class PlayerMobTvHat {

    /** The chance is 1 in this. */
    static final int ONE_IN = 15;

    /** Vanilla's default armour-slot drop chance ({@code Mob#armorDropChances}). */
    private static final float VANILLA_DROP_CHANCE = 0.085f;

    private PlayerMobTvHat() {}

    /** The 1-in-{@link #ONE_IN} roll on its own, for tests. */
    static boolean rolls(RandomSource rng) {
        return rng.nextInt(ONE_IN) == 0;
    }

    /** Puts a TV on {@code mob}'s head on a winning roll, unless it is an echo. */
    public static void maybeWear(Mob mob, RandomSource rng) {
        if (EchoIdentity.sourcePlayer(mob).isPresent() || !rolls(rng)) return;
        Item tv = BuiltInRegistries.ITEM.get(TvBlocks.TV_ID);
        if (tv == Items.AIR) return;
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(tv));
        mob.setDropChance(EquipmentSlot.HEAD, VANILLA_DROP_CHANCE);
    }
}
