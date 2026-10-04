package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Gives every server-side PlayerMob a {@link PlayerMobPhotoGoal}. Priority 1 sits with PlayerMob's
 * own interruptible actions (door work, tool use) — below its fire-escape, above combat — so a
 * pending photo request is acted on next, and is dropped the moment a fight starts.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerMobPhotoEvents {

    private static final int PRIORITY = 1;

    private PlayerMobPhotoEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof PlayerMobEntity mob)) return;
        PlayerMobPhotoGoal.create(mob).ifPresent(goal -> mob.goalSelector.addGoal(PRIORITY, goal));
    }
}
