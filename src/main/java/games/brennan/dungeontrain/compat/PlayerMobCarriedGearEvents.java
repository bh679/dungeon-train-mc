package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.playermob.entity.CarriedGear;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Rolls how much backpack gear each PlayerMob wears on its body ({@link CarriedGearRoll}) the
 * first time it enters a server level — train spawns, echoes, companions and natural spawns alike.
 * PlayerMob saves the result, so a mob that already rolled keeps its look across reloads.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerMobCarriedGearEvents {

    private PlayerMobCarriedGearEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof PlayerMobEntity mob)) return;
        if (mob.getCarriedGearLimit() != CarriedGear.NO_LIMIT) return;
        mob.setCarriedGearLimit(CarriedGearRoll.roll(mob.getRandom()));
    }
}
