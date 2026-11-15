package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * When a bed offers a nap ({@link NapService}).
 *
 * <ul>
 *   <li><b>By day</b> a natural bed answers vanilla's "You can only sleep at night" with the daytime
 *       prompt. The refusal is swapped for {@code OTHER_PROBLEM}, which vanilla shows no message for;
 *       every other refusal (monsters nearby, obstructed, too far) is left alone.</li>
 *   <li><b>At night</b> the prompt comes once the player has drifted off
 *       ({@link Player#isSleepingLongEnough}), once per sleep.</li>
 * </ul>
 *
 * <p>Also feeds {@link PlacedBedTracker}: a bed a player places is recorded, and forgotten when broken.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapBedEvents {

    /** Players already offered a nap in their current sleep. Server-thread only. */
    private static final Set<UUID> OFFERED_THIS_SLEEP = new HashSet<>();

    private NapBedEvents() {}

    @SubscribeEvent
    public static void onBedPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getPlacedBlock();
        if (state.getBlock() instanceof BedBlock) {
            PlacedBedTracker.get(level).markPlaced(event.getPos(), state);
        }
    }

    @SubscribeEvent
    public static void onBedBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        if (state.getBlock() instanceof BedBlock) {
            PlacedBedTracker.get(level).forget(event.getPos(), state);
        }
    }

    @SubscribeEvent
    public static void onTrySleep(CanPlayerSleepEvent event) {
        if (event.getProblem() != Player.BedSleepingProblem.NOT_POSSIBLE_NOW) return;
        if (NapService.offer(event.getEntity(), event.getPos(), false)) {
            event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isSleeping()) {
            OFFERED_THIS_SLEEP.remove(player.getUUID());
            return;
        }
        if (!player.isSleepingLongEnough() || !OFFERED_THIS_SLEEP.add(player.getUUID())) return;
        player.getSleepingPos().ifPresent(bed -> NapService.offer(player, bed, true));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OFFERED_THIS_SLEEP.remove(event.getEntity().getUUID());
        NapService.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OFFERED_THIS_SLEEP.clear();
        NapService.clear();
    }
}
