package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Gives every server-side PlayerMob a {@link PlayerMobPhotoGoal}. Priority 0 — PlayerMob's
 * self-preservation tier — so a pending photo outranks greeting and gift-giving (priority 1): the
 * mob stops bowing or handing things over and takes the picture first. The goal itself yields to
 * what matters more: it aborts the moment the mob is in combat, fleeing, recovering onto the train,
 * or on fire, so the fire-escape goal that shares this tier is never held off.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerMobPhotoEvents {

    private static final int PRIORITY = 0;

    private PlayerMobPhotoEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof PlayerMobEntity mob)) return;
        PlayerMobPhotoGoal.create(mob).ifPresent(goal -> mob.goalSelector.addGoal(PRIORITY, goal));
    }
}
