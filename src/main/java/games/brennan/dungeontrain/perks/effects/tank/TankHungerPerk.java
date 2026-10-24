package games.brennan.dungeontrain.perks.effects.tank;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tank tier 2 "Hunger": hunger drains {@link TankPerks#HUNGER_REFUND_PER_RANK} slower per rank (capped at
 * {@link TankPerks#HUNGER_REFUND_CAP}). Vanilla pays every action into {@code FoodData.exhaustionLevel}
 * and takes a food/saturation point each time it crosses 4.0. Rather than a mixin on
 * {@code addExhaustion}, this watches the level once per tick, works out what vanilla spent since the last
 * look ({@link TankPerks#exhaustionSpent}, wrap-aware) and hands the perk's share straight back with
 * {@code setExhaustion}, so the counter climbs that much slower.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TankHungerPerk {

    /** Exhaustion level seen at the end of each player's last tick. */
    private static final Map<UUID, Float> LAST_SEEN = new ConcurrentHashMap<>();

    private TankHungerPerk() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        FoodData food = player.getFoodData();
        float current = food.getExhaustionLevel();
        Float previous = LAST_SEEN.put(player.getUUID(), current);
        if (previous == null) return;
        int rank = Perks.rank(player, TankPerks.HUNGER);
        double refund = TankPerks.hungerRefundFraction(rank);
        if (refund <= 0.0) return;
        float spent = TankPerks.exhaustionSpent(previous, current);
        if (spent <= 0.0F) return;
        float adjusted = Math.max(0.0F, current - (float) (spent * refund));
        food.setExhaustion(adjusted);
        LAST_SEEN.put(player.getUUID(), adjusted);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SEEN.remove(event.getEntity().getUUID());
    }
}
