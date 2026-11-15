package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 * <p>Also feeds {@link PlacedRestTracker}: a bed or respawn anchor a player places is recorded, and
 * forgotten when broken. Anchors offer their nap from {@code RespawnAnchorBlockMixin}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapBedEvents {

    /** Players already offered a nap in their current sleep. Server-thread only. */
    private static final Set<UUID> OFFERED_THIS_SLEEP = new HashSet<>();
    /** Marks a bed item dropped by a natural bed ({@code custom_data}). */
    static final String TRAIN_BED_TAG = "dungeontrain:train_bed";
    /** Game time a player last right-clicked with a train bed item. Server-thread only. */
    private static final Map<UUID, Long> TRAIN_BED_USE = new HashMap<>();
    /** Both halves of natural beds being broken this tick, until their drops come through. */
    private static final LongOpenHashSet NATURAL_BREAKING = new LongOpenHashSet();

    private NapBedEvents() {}

    /** Remembers a player right-clicking with a natural bed's own item, for the place event that follows. */
    @SubscribeEvent
    public static void onUseItemOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (isTrainBed(event.getItemStack())) {
            TRAIN_BED_USE.put(event.getEntity().getUUID(), event.getLevel().getGameTime());
        }
    }

    @SubscribeEvent
    public static void onBedPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getPlacedBlock();
        if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock) {
            Long used = TRAIN_BED_USE.remove(player.getUUID());
            boolean moved = state.getBlock() instanceof BedBlock && used != null && used == level.getGameTime();
            PlacedRestTracker.get(level).markPlaced(event.getPos(), state,
                moved ? PlacedRestTracker.Kind.MOVED : PlacedRestTracker.Kind.MADE);
        }
    }

    @SubscribeEvent
    public static void onBedBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockState state = event.getState();
        if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock) {
            PlacedRestTracker placed = PlacedRestTracker.get(level);
            if (state.getBlock() instanceof BedBlock && !placed.isPlayerPlaced(event.getPos())) {
                // Natural: the bed item it drops (from whichever half drops) is the train's own.
                NATURAL_BREAKING.add(event.getPos().asLong());
                NATURAL_BREAKING.add(PlacedRestTracker.otherHalf(event.getPos(), state).asLong());
            }
            placed.forget(event.getPos(), state);
        }
    }

    /** Marks the bed item a natural bed drops, so placing it again reads as "moved", not "made". */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (NATURAL_BREAKING.isEmpty() || !NATURAL_BREAKING.remove(event.getPos().asLong())) return;
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.getItem() instanceof BedItem) {
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(TRAIN_BED_TAG, true));
            }
        }
    }

    static boolean isTrainBed(ItemStack stack) {
        if (!(stack.getItem() instanceof BedItem)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(TRAIN_BED_TAG);
    }

    @SubscribeEvent
    public static void onTrySleep(CanPlayerSleepEvent event) {
        if (event.getProblem() != Player.BedSleepingProblem.NOT_POSSIBLE_NOW) return;
        ServerPlayer player = event.getEntity();
        NapService.Outcome outcome = NapService.offer(player, event.getPos(), false);
        switch (outcome) {
            case OFFERED -> event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
            case MONSTERS -> event.setProblem(Player.BedSleepingProblem.NOT_SAFE);
            case MOVED, MADE -> {
                event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
                player.displayClientMessage(NapService.tamperedMessage(outcome), true);
            }
            case NONE -> { }
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
        player.getSleepingPos().ifPresent(bed -> {
            Component tampered = NapService.tamperedMessage(NapService.offer(player, bed, true));
            if (tampered != null) player.displayClientMessage(tampered, true);
        });
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OFFERED_THIS_SLEEP.remove(event.getEntity().getUUID());
        TRAIN_BED_USE.remove(event.getEntity().getUUID());
        NapService.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        NATURAL_BREAKING.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OFFERED_THIS_SLEEP.clear();
        TRAIN_BED_USE.clear();
        NapService.clear();
    }
}
