package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.train.SharedCarriageLookup;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Keeps the {@link games.brennan.dungeontrain.registry.effect.DriftingEffect "Drifting"} marker on
 * a player for as long as they are inside a drifting carriage (standing on it) or a drifting room
 * (inside its box). Polled on the same coarse cadence as {@link SharedCarriageEnterEvents}; each hit
 * refreshes a short effect, so leaving lets it run out on its own within {@link #EFFECT_TICKS}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DriftingEffectEvents {

    static final int CHECK_INTERVAL_TICKS = 10;
    /**
     * Long enough to outlast a jump: footing needs a block at the feet, so a jump on a carriage reads
     * as "off" for a poll or two. Short enough that walking out clears the icon within ~2 s.
     */
    static final int EFFECT_TICKS = 40;

    private DriftingEffectEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        if (player.isSpectator() || player.isDeadOrDying()) return;
        if (!SharedCarriageGate.canDiscover()) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!isDrifting(level, player)) return;
        player.addEffect(new MobEffectInstance(
            ModMobEffects.DRIFTING,
            EFFECT_TICKS,
            0,
            true,   // ambient — quieter HUD swirls
            true    // showIcon
        ));
    }

    private static boolean isDrifting(ServerLevel level, ServerPlayer player) {
        SharedCarriageRegistry.Instance carriage = SharedCarriageLookup.byEntity(level, player);
        if (carriage != null && !carriage.isCulled()) return true;
        SharedRoomRegistry.Instance room = SharedRoomRegistry.byWorldPos(level, player.blockPosition());
        return room != null && !room.isCulled();
    }
}
