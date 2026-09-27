package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Swaps {@link DisabledModContent} gear on mobs for the vanilla equivalent as they join a level — chiefly
 * BetterNether's city-tower spawners, whose mobs are baked into the structure wearing cincinnasite armour
 * with a cincinnasite-diamond sword. Their death drops bypass loot tables, so without this the gear would
 * still reach players. Iron by default, diamond where the mod item was a diamond variant, so the guards
 * stay as tough; the mob's drop chances are untouched.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DisabledModMobGear {

    private DisabledModMobGear() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack worn = mob.getItemBySlot(slot);
            if (DisabledModContent.isDisabledItem(worn)) {
                mob.setItemSlot(slot, replacementFor(worn));
            }
        }
    }

    private static ItemStack replacementFor(ItemStack worn) {
        Item item = worn.getItem();
        String id = vanillaReplacementId(BuiltInRegistries.ITEM.getKey(item).getPath(), kindOf(item));
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(id)), worn.getCount());
    }

    /** Coarse gear kind, read from the item class; {@code null} when there is no vanilla stand-in. */
    private static String kindOf(Item item) {
        if (item instanceof ArmorItem armor) {
            return switch (armor.getType()) {
                case HELMET -> "helmet";
                case CHESTPLATE -> "chestplate";
                case LEGGINGS -> "leggings";
                case BOOTS -> "boots";
                default -> null;
            };
        }
        if (item instanceof SwordItem) return "sword";
        if (item instanceof PickaxeItem) return "pickaxe";
        if (item instanceof ShovelItem) return "shovel";
        if (item instanceof HoeItem) return "hoe";
        if (item instanceof AxeItem || item instanceof TieredItem) return "axe"; // hammers, excavators
        if (item instanceof CrossbowItem) return "crossbow";
        if (item instanceof BowItem) return "bow";
        if (item instanceof ShieldItem) return "shield";
        return null;
    }

    /** Vanilla item path standing in for a disabled mod item of the given kind; {@code null} = remove it. */
    static String vanillaReplacementId(String modPath, String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "bow", "crossbow", "shield" -> kind;
            default -> (modPath.contains("diamond") ? "diamond_" : "iron_") + kind;
        };
    }
}
