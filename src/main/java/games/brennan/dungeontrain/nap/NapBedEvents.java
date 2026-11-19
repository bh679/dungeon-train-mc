package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.DungeonTrain;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
        if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock
                || RespawnObelisks.isObelisk(state)) {
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
        if (state.getBlock() instanceof BedBlock || state.getBlock() instanceof RespawnAnchorBlock
                || RespawnObelisks.isObelisk(state)) {
            PlacedRestTracker placed = PlacedRestTracker.get(level);
            if (state.getBlock() instanceof BedBlock && !placed.isPlayerPlaced(event.getPos())) {
                // Natural: the bed item it drops (from whichever half drops) is the train's own.
                NATURAL_BREAKING.add(event.getPos().asLong());
                NATURAL_BREAKING.add(PlacedRestTracker.otherHalf(event.getPos(), state).asLong());
            }
            placed.forget(event.getPos(), state);
            if (RespawnObelisks.isObelisk(state)) {
                RespawnObeliskGems.get(level).forget(RespawnObelisks.bottom(event.getPos(), state));
            }
        }
    }

    /**
     * A Respawn Obelisk clicked in survival, decided here on the server before DT's disabled-block veto
     * (which lets obelisks through so the click reaches the server). A natural one explodes outside an
     * End core. In one, and only on the train, it offers a nap (or says monsters are nearby) once it holds
     * {@value RespawnObeliskGems#MAX} Amber Gems ({@link RespawnObeliskGems}); until then a click with
     * Amber Gems puts them in, and any other click says how many more it needs. It never sets a spawn
     * point, so the click is always consumed: what isn't a nap or an explosion stays as blocked as before.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onObeliskUse(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getEntity().isCreative()) return;
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!RespawnObelisks.isObelisk(state)) return;
        boolean natural = !PlacedRestTracker.get(level).isPlayerPlaced(RespawnObelisks.bottom(pos, state));
        InteractionResult result = InteractionResult.FAIL;
        if (natural && !HostileBandBeds.obeliskWorks(level, pos)) {
            RespawnObelisks.explode(level, pos, state);
            result = InteractionResult.SUCCESS;
        } else if (natural && NapEligibility.trainSubLevelAt(level, pos) != null) {
            // Naps are train-only: off the train an obelisk takes no gems and offers nothing.
            result = useNaturalObelisk(event, level, player, pos, state);
        }
        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    /** An obelisk in an End core: fed Amber Gems until full, then the nap spot. */
    private static InteractionResult useNaturalObelisk(PlayerInteractEvent.RightClickBlock event, ServerLevel level,
                                                       ServerPlayer player, BlockPos pos, BlockState state) {
        RespawnObeliskGems gems = RespawnObeliskGems.get(level);
        BlockPos bottom = RespawnObelisks.bottom(pos, state);
        if (gems.get(bottom) < RespawnObeliskGems.MAX) {
            ItemStack held = event.getItemStack();
            if (!RespawnObelisks.isAmberGem(held)) {
                // The other hand's click feeds it, when that hand holds the gems.
                if (RespawnObelisks.isAmberGem(player.getItemInHand(otherHand(event.getHand())))) {
                    return InteractionResult.FAIL;
                }
                showNeeds(player, gems.get(bottom));
                return InteractionResult.FAIL;
            }
            int taken = gems.add(bottom, held.getCount());
            if (taken > 0) {
                held.shrink(taken);
                level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
            if (gems.get(bottom) < RespawnObeliskGems.MAX) {
                showNeeds(player, gems.get(bottom));
                return InteractionResult.SUCCESS;
            }
        }
        NapService.Outcome outcome = NapService.offer(player, pos, false);
        if (outcome == NapService.Outcome.MONSTERS) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.not_safe"), true);
        }
        if (outcome == NapService.Outcome.GUESTS) player.displayClientMessage(NapService.tamperedMessage(outcome), true);
        return outcome == NapService.Outcome.OFFERED ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static void showNeeds(ServerPlayer player, int held) {
        player.displayClientMessage(games.brennan.dungeontrain.narrative.PluralRules.clause(
            games.brennan.dungeontrain.discord.WorldInfoReporter.clientLanguage(player),
            "chat.dungeontrain.nap.obelisk_needs", RespawnObeliskGems.MAX - held), true);
    }

    private static InteractionHand otherHand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
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
            case MOVED, MADE, GUESTS -> {
                event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
                NapService.tellRefusal(player, outcome);
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
        player.getSleepingPos().ifPresent(bed -> NapService.tellRefusal(player, NapService.offer(player, bed, true)));
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
