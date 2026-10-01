package games.brennan.dungeontrain.echo;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Credits echo gifts. PlayerMob's {@code tossGift} spawns the gift as an {@link ItemEntity} with the
 * giving mob as its thrower — the only PlayerMob path that sets one — so a freshly spawned item thrown
 * by an echo is a gift. Items reloaded from disk are skipped; they were credited when first tossed.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EchoGiftCreditEvents {

    private EchoGiftCreditEvents() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()) return;
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        if (item.getOwner() instanceof PlayerMobEntity mob) {
            EchoDropCredit.onGift(mob, item.getItem());
        }
    }
}
