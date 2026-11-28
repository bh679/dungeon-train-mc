package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.FirstAppleBoost;
import games.brennan.dungeontrain.perks.PerksGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives {@link FirstAppleBoost}: a container opened (before its menu shows, so a cleared or restored extra
 * is what the player sees), a marked container or pot broken, and a once-a-second check that fills the groups
 * already built when the boost comes on and settles the chest waiting to be emptied or left behind. Clicks arrive in plot space for carriage blocks, as {@link LuckyLootEvents} relies on.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FirstAppleEvents {

    /** Ticks between checks of the open chest. */
    private static final int CHECK_EVERY_TICKS = 20;

    private FirstAppleEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!PerksGate.isOn()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Sneaking with a block in hand places against the container instead of opening it.
        if (player.isShiftKeyDown() && event.getItemStack().getItem() instanceof BlockItem) return;
        FirstAppleBoost.onOpen(level, player, event.getPos());
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!PerksGate.isOn()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FirstAppleBoost.onBreak(level, event.getPlayer() instanceof ServerPlayer p ? p : null, event.getPos());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PerksGate.isOn()) return;
        if (event.getServer().getTickCount() % CHECK_EVERY_TICKS != 0) return;
        FirstAppleBoost.backfillIfArmed(event.getServer());
        games.brennan.dungeontrain.perks.TimedPotApple.tick(event.getServer());
        FirstAppleBoost.tick(event.getServer());
    }
}
