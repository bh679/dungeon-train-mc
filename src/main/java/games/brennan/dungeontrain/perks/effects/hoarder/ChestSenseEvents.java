package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Chest Sense, server side. Once a second, for players with the perk: when the pulse is due, scan
 * ({@link ChestSense#scan}), send what was found ({@link ChestSensePacket}) with a soft chime, and set
 * the next pulse {@value ChestSense#COOLDOWN_MINUTES} minutes on. The first pulse comes as soon as
 * the perk is active. The next-pulse time is a top-level persistent-data key, so it resets each life
 * and survives logout.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ChestSenseEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChestSenseEvents.class);

    static final String NEXT_KEY = "dungeontrain:chest_sense_next";
    private static final int CHECK_TICKS = 20;

    private ChestSenseEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_TICKS != 0 || !player.isAlive() || player.isSpectator()) return;
        try {
            int rank = Perks.rank(player, ChestSense.ID);
            if (rank <= 0) return;
            long now = player.serverLevel().getGameTime();
            long next = player.getPersistentData().getLong(NEXT_KEY);
            // A next time far past now means the world's clock went back (a different world): pulse.
            if (now < next && next - now <= ChestSense.cooldownTicks()) return;
            pulse(player, rank, now);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Chest Sense failed for {}", player.getName().getString(), e);
        }
    }

    /** Senses now and sets the next pulse. Package-private for the dev harness and tests. */
    static void pulse(ServerPlayer player, int rank, long now) {
        player.getPersistentData().putLong(NEXT_KEY, now + ChestSense.cooldownTicks());
        List<ChestSense.Sensed> found = ChestSense.scan(player.serverLevel(), player.getEyePosition(), ChestSense.range(rank));
        if (found.isEmpty()) return;
        PacketDistributor.sendToPlayer(player, new ChestSensePacket(ChestSense.glowSeconds(rank) * 20, found));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.4F);
    }
}
