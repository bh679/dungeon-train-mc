package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.LiveStreamPacket;
import games.brennan.dungeontrain.player.LiveStreamers;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import games.brennan.dungeontrain.compat.vista.LiveBroadcastLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Server half of the Live Feed: the broadcast headpiece.
 *
 * <p>Putting the headpiece on is the only way to become the streamer. The server sees the HEAD
 * slot change, burns the item away (flame + hiss), remembers the wearer in {@link LiveStreamers},
 * tells any previous streamer on this server they were replaced, and sends the wearer
 * {@link LiveStreamPacket.Action#START}. The wearer's client then claims the channel from the
 * relay — which is where cross-world takeover is decided; two single-player worlds each have a
 * "streamer", and the relay's last claim wins. Death or logout ends the stream.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveFeedEvents {

    private LiveFeedEvents() {}

    /** Every server level learns that the feed id lives at the block-less live location. */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) LiveBroadcastLocation.link(level);
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.HEAD) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack to = event.getTo();
        if (to.isEmpty() || !to.is(ModItems.LIVE_HEADPIECE.get())) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Burns away: the item is spent the moment it touches the head.
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getEyeY() + 0.3, player.getZ(), 24, 0.3, 0.2, 0.3, 0.02);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getEyeY() + 0.4, player.getZ(), 8, 0.2, 0.1, 0.2, 0.01);
        level.playSound(null, player.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8f, 0.9f);

        UUID previous = LiveStreamers.setStreamer(server, player.getUUID());
        if (previous != null && !previous.equals(player.getUUID())) {
            ServerPlayer old = server.getPlayerList().getPlayer(previous);
            if (old != null) {
                PacketDistributor.sendToPlayer(old, LiveStreamPacket.stop(LiveStreamPacket.Action.STOP_REPLACED, player.getGameProfile().getName()));
            }
        }
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.live.headpiece_burns"));
        PacketDistributor.sendToPlayer(player, LiveStreamPacket.start());
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        stopIfStreamer(player, LiveStreamPacket.Action.STOP_DIED);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // The connection is going away; the packet may not land, but the client stops itself on
        // LoggingOut too. This mainly keeps LiveStreamers honest.
        stopIfStreamer(player, LiveStreamPacket.Action.STOP_LEFT);
    }

    private static void stopIfStreamer(ServerPlayer player, LiveStreamPacket.Action why) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!LiveStreamers.clearIf(server, player.getUUID())) return;
        PacketDistributor.sendToPlayer(player, LiveStreamPacket.stop(why, ""));
    }
}
