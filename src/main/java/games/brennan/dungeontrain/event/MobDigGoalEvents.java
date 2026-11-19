package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.combat.MobDigGoal;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Re-gives a tool mob its {@link MobDigGoal} when it loads from disk. Goals are not saved; the mob's
 * break tier is (persistent data), and a freshly spawned one gets its goal from the difficulty roll.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MobDigGoalEvents {

    private MobDigGoalEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        MobDigGoal.attach(mob);
    }
}
