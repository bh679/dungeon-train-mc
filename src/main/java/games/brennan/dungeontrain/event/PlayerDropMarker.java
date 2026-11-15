package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.slf4j.Logger;

import java.util.Set;

/**
 * Tags items a player put on the ground — tossed, dropped on death, or dropped by a mob the player
 * killed — so the train's runway sweep ({@link TrainTickEvents}) leaves them on the track. The tag
 * is an entity tag, so it persists with the item through chunk unload.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerDropMarker {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String PLAYER_DROP_TAG = "dungeontrain_player_drop";

    private PlayerDropMarker() {}

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        ItemEntity item = event.getEntity();
        if (item.level().isClientSide()) return;
        item.addTag(PLAYER_DROP_TAG);
    }

    /** LOWEST, so drops other handlers add are already in the list. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrops(LivingDropsEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (entity.level().isClientSide()) return;
            boolean playerEarned = entity instanceof Player
                || event.getSource().getEntity() instanceof Player
                || entity.getKillCredit() instanceof Player;
            if (!playerEarned) return;
            for (ItemEntity drop : event.getDrops()) drop.addTag(PLAYER_DROP_TAG);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] player drop marking failed: {}", t.toString());
        }
    }

    /** True for an item a player dropped or earned, which the runway sweep must not discard. */
    public static boolean isPlayerDrop(Entity e) {
        if (!(e instanceof ItemEntity item)) return false;
        return hasPlayerDropTag(item.getTags()) || item.getOwner() instanceof Player;
    }

    /** Registry-free seam for {@link #isPlayerDrop}, unit-testable without a live entity. */
    static boolean hasPlayerDropTag(Set<String> tags) {
        return tags.contains(PLAYER_DROP_TAG);
    }
}
