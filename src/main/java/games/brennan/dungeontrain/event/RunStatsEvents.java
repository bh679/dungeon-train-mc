package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DeathStatsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Per-run stat tracking for the death-screen summary. All increments live
 * server-side on {@link PlayerRunState} and survive logout via the
 * attachment codec; they are cleared on respawn by
 * {@link AchievementEvents#onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent)}.
 *
 * <p>Hooks:</p>
 * <ul>
 *   <li>{@link LivingDeathEvent} — increments {@code mobKills} on the killer
 *       when victim is any {@link LivingEntity} other than the killer. On
 *       the LOW-priority pass, snapshots the dying player's stats and sends
 *       them to that player via {@link DeathStatsPacket}.</li>
 *   <li>{@link PlayerTickEvent.Post} — per-tick {@code runTicks++} on
 *       server players.</li>
 *   <li>{@link BlockEvent.BreakEvent} — counts decorated-pot breaks as
 *       container opens.</li>
 *   <li>{@link PlayerInteractEvent.RightClickItem} — counts held
 *       written-book right-clicks (vanilla + narrative — narrative books
 *       are vanilla {@code written_book} items with extra NBT).</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RunStatsEvents {

    private RunStatsEvents() {}

    @SubscribeEvent
    public static void onMobKilled(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        // Don't credit a player for killing themselves (TNT, fall while
        // holding a damaging item, etc.). Stat is "things I killed" not
        // "deaths I caused".
        if (victim == killer) return;
        PlayerRunState run = killer.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        run.incrementMobKills();
        // Snapshot the main-hand stack so the "most used weapon" tally
        // reflects what the player was actually holding at the moment of
        // the kill. Bare-hand kills count as minecraft:air and are filtered
        // out of the final pick by PlayerRunState#mostUsedWeapon.
        run.recordWeaponKill(killer.getMainHandItem().copy());
    }

    /**
     * LOW priority so any higher-priority kill-credit / scoring handlers
     * (vanilla and other mods) finish before we snapshot the stats and
     * push them down to the client. Runs only when the victim is the
     * player itself; the kill-counter pass above already handled the
     * killer's tally.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerRunState run = player.getData(ModDataAttachments.PLAYER_RUN_STATE.get());
        // Snapshot armor at death — the keep-inventory gamerule and respawn
        // both run AFTER LivingDeathEvent, so the equipment slots still
        // reflect what the player was wearing when they died.
        DeathStatsPacket packet = new DeathStatsPacket(
                run.mobKills(),
                run.cartsSinceDeath(),
                run.distanceBlocks(),
                run.runTicks(),
                run.containersOpened(),
                run.booksReadCount(),
                run.mostUsedWeapon(),
                player.getItemBySlot(EquipmentSlot.HEAD).copy(),
                player.getItemBySlot(EquipmentSlot.CHEST).copy(),
                player.getItemBySlot(EquipmentSlot.LEGS).copy(),
                player.getItemBySlot(EquipmentSlot.FEET).copy()
        );
        DungeonTrainNet.sendTo(player, packet);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addRunTicks(1L);
    }

    @SubscribeEvent
    public static void onPotBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        BlockState state = event.getState();
        if (!(state.getBlock() instanceof DecoratedPotBlock)) return;
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).incrementContainersOpened();
    }

    @SubscribeEvent
    public static void onBookRead(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!(stack.getItem() instanceof WrittenBookItem)) return;
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).incrementBooksRead();
    }
}
