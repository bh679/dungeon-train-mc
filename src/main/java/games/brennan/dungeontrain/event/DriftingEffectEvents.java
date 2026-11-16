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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the {@link games.brennan.dungeontrain.registry.effect.DriftingEffect "Drifting"} marker on
 * a player for as long as they are inside a drifting carriage (standing on it) or a drifting room
 * (inside its box). Polled on the same coarse cadence as {@link SharedCarriageEnterEvents}.
 *
 * <p>The effect is indefinite while inside — no countdown, and no end-of-effect blinking on the HUD
 * icon — and is removed once the player has been outside for {@link #OFF_GRACE_POLLS} polls in a
 * row.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DriftingEffectEvents {

    static final int CHECK_INTERVAL_TICKS = 10;
    /**
     * Consecutive polls outside before the effect comes off (~2 s). Footing needs a block at the feet,
     * so a jump on a carriage reads as "outside" for a poll or two; this rides that out.
     */
    static final int OFF_GRACE_POLLS = 4;

    /** Consecutive polls each player holding the effect has been found outside. */
    private static final Map<UUID, Integer> OFF_POLLS = new ConcurrentHashMap<>();

    private DriftingEffectEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player p = event.getEntity();
        if (p.level().isClientSide() || !(p instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        boolean inside = !player.isSpectator() && !player.isDeadOrDying()
                && SharedCarriageGate.canDiscover() && isDrifting(level, player);
        if (inside) {
            OFF_POLLS.remove(player.getUUID());
            MobEffectInstance current = player.getEffect(ModMobEffects.DRIFTING);
            if (current == null || !current.isInfiniteDuration()) {
                player.addEffect(new MobEffectInstance(
                    ModMobEffects.DRIFTING,
                    MobEffectInstance.INFINITE_DURATION,
                    0,
                    true,   // ambient — quieter HUD swirls
                    true    // showIcon
                ));
            }
            return;
        }
        if (!player.hasEffect(ModMobEffects.DRIFTING)) {
            OFF_POLLS.remove(player.getUUID());
            return;
        }
        if (OFF_POLLS.merge(player.getUUID(), 1, Integer::sum) >= OFF_GRACE_POLLS) {
            player.removeEffect(ModMobEffects.DRIFTING);
            OFF_POLLS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OFF_POLLS.remove(event.getEntity().getUUID());
    }

    private static boolean isDrifting(ServerLevel level, ServerPlayer player) {
        SharedCarriageRegistry.Instance carriage = SharedCarriageLookup.byEntity(level, player);
        if (carriage != null && !carriage.isCulled()) return true;
        SharedRoomRegistry.Instance room = SharedRoomRegistry.byWorldPos(level, player.blockPosition());
        return room != null && !room.isCulled();
    }
}
