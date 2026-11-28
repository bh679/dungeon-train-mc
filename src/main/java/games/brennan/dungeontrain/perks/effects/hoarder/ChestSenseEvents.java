package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Chest Sense, server side. Once a second, for players with the perk: when the pulse is due, scan
 * ({@link ChestSense#scan}), send what was found and the range ({@link ChestSensePacket}, which also
 * starts the client's particle ring) with a soft chime, and set the next pulse
 * {@link ChestSense#everyMinutes} on. The first pulse comes as soon as the perk is active. The
 * next-pulse time is a top-level persistent-data key, so it resets each life and survives logout.
 *
 * <p>It also stamps {@link ChestSense#OPENED_KEY} on any container a player right-clicks, for anyone,
 * perk or not, so an opened container stops glowing for everyone.</p>
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
            if (now < next && next - now <= ChestSense.intervalTicks(1)) return;
            pulse(player, rank, now);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Chest Sense failed for {}", player.getName().getString(), e);
        }
    }

    /** A right-clicked container counts as opened from then on, so Chest Sense stops showing it. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity().isSecondaryUseActive() && !event.getEntity().getMainHandItem().isEmpty()) return;
        BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        // A vault stops glowing for a player once it has rewarded them, not when it is clicked.
        if (be == null || ChestSense.isVase(be) || be instanceof VaultBlockEntity || !ChestSense.isSensedKind(be)) return;
        markOpened(be);
        // A double chest is two block entities: the half not clicked was opened too.
        BlockState state = be.getBlockState();
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockEntity other = event.getLevel().getBlockEntity(event.getPos().relative(ChestBlock.getConnectedDirection(state)));
            if (other != null && ChestSense.isSensedKind(other)) markOpened(other);
        }
    }

    private static void markOpened(BlockEntity be) {
        if (be.getPersistentData().getBoolean(ChestSense.OPENED_KEY)) return;
        be.getPersistentData().putBoolean(ChestSense.OPENED_KEY, true);
        be.setChanged();
    }

    /** Senses now and sets the next pulse. Package-private for the dev harness and tests. */
    static void pulse(ServerPlayer player, int rank, long now) {
        player.getPersistentData().putLong(NEXT_KEY, now + ChestSense.intervalTicks(rank));
        double range = ChestSense.range(rank);
        List<ChestSense.Sensed> found = ChestSense.scan(player.serverLevel(), player, player.getEyePosition(), range);
        PacketDistributor.sendToPlayer(player, new ChestSensePacket(ChestSense.glowTicks(), (float) range, found));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.4F);
    }
}
