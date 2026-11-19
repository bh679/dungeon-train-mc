package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Ordinary Piglin Brute spawn eggs spawn without a Buckler.
 *
 * <p>Piglin Proliferation hands every brute a Buckler in its off-hand on {@link FinalizeSpawnEvent}, and
 * the charge attack runs only while one is held. Carriage authors pick which brutes charge with
 * {@code dungeontrain:buckler_piglin_brute_spawn_egg} instead, so a plain egg (or a dispensed one) has
 * the Buckler taken back here, at {@link EventPriority#LOWEST} so it runs after the mod's handler. The
 * Buckler egg survives it: its {@code ENTITY_DATA} is applied after finalizeSpawn. Natural, structure
 * and command spawns keep the mod's Buckler. Carriage mobs never reach this event (the placer calls
 * {@code Mob#finalizeSpawn} directly).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BruteBucklerSpawnRule {

    static final ResourceLocation BUCKLER = ResourceLocation.fromNamespaceAndPath("piglinproliferation", "buckler");

    private BruteBucklerSpawnRule() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!(event.getEntity() instanceof PiglinBrute brute)) return;
        if (!isEggSpawn(event.getSpawnType())) return;
        if (isBuckler(brute.getOffhandItem())) {
            brute.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
    }

    static boolean isEggSpawn(MobSpawnType type) {
        return type == MobSpawnType.SPAWN_EGG || type == MobSpawnType.DISPENSER;
    }

    private static boolean isBuckler(ItemStack stack) {
        return !stack.isEmpty() && BUCKLER.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
