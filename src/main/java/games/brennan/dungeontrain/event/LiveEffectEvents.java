package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.LiveStreamers;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Keeps the {@link games.brennan.dungeontrain.registry.effect.LiveEffect "Live"} marker on a player
 * for exactly as long as they are streaming with the Live Eye — a channel the relay granted
 * ({@link LiveStreamers#isStreamer}), not merely the Eye worn while it is still claiming. Indefinite
 * while live, removed on the first poll after the stream ends.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveEffectEvents {

    static final int CHECK_INTERVAL_TICKS = 10;

    private LiveEffectEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        boolean live = !player.isDeadOrDying() && LiveStreamers.isStreamer(player.server, player.getUUID());
        MobEffectInstance current = player.getEffect(ModMobEffects.LIVE);
        if (live && (current == null || !current.isInfiniteDuration())) {
            player.addEffect(new MobEffectInstance(
                ModMobEffects.LIVE,
                MobEffectInstance.INFINITE_DURATION,
                0,
                true,   // ambient — quieter HUD swirls
                true    // showIcon
            ));
        } else if (!live && current != null) {
            player.removeEffect(ModMobEffects.LIVE);
        }
    }
}
