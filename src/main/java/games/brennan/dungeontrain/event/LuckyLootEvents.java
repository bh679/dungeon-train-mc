package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.LuckyBonusRoller;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Spends a train chest's pre-rolled Luck bonus ({@link LuckyBonusRoller}) the first time
 * a player opens it. A lucky opener gets 1–3 of the candidates dropped into empty slots
 * before the menu opens; an unlucky opener gets nothing — and either way the bonus is
 * consumed, so an already-looted chest can't be revisited with a potion.
 *
 * <p>{@link PlayerInteractEvent.RightClickBlock} rather than {@code PlayerContainerEvent.Open}
 * because the click hands us the block entity directly (the open event only carries the
 * menu, whose backing container is a {@code CompoundContainer} for double chests). The
 * event fires in plot space for carriage blocks, so no world→ship transform is needed —
 * and the presence of the bonus key is itself the "this is DT-rolled train loot" test,
 * so no carriage lookup is needed either.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LuckyLootEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Action-bar line shown when the bonus lands; {@code %s} = items added. */
    private static final String MSG_BONUS = "dungeontrain.lucky_loot.bonus";

    private LuckyLootEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // RightClickBlock fires once per hand — act on the main hand only.
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!LuckyBonusRoller.isBonusContainer(state)) return;
        // Sneaking with a block in hand places against the chest instead of opening it.
        if (player.isShiftKeyDown() && event.getItemStack().getItem() instanceof BlockItem) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container) || !LuckyBonusRoller.hasBonus(be)) return;

        List<ItemStack> candidates = LuckyBonusRoller.takeBonus(be, level.registryAccess());
        int wanted = LuckyBonusRoller.bonusCountFor(player.getLuck(), level.getRandom());
        if (wanted <= 0 || candidates.isEmpty()) return;

        int added = placeIntoEmptySlots(container, candidates, wanted);
        if (added <= 0) return;
        be.setChanged();
        player.displayClientMessage(Component.translatable(MSG_BONUS, added), true);
        LOGGER.debug("[lucky-loot] {} opened {} at {} with luck {} -> +{} bonus item(s)",
            player.getGameProfile().getName(), state.getBlock().getName().getString(), pos,
            player.getLuck(), added);
    }

    /**
     * Drop up to {@code wanted} of {@code candidates} into the container's empty slots, in
     * order. Stops when the container is full — leftovers are simply forfeited.
     *
     * @return how many stacks were placed
     */
    private static int placeIntoEmptySlots(Container container, List<ItemStack> candidates, int wanted) {
        int placed = 0;
        int slot = 0;
        int limit = Math.min(wanted, candidates.size());
        while (placed < limit && slot < container.getContainerSize()) {
            if (container.getItem(slot).isEmpty()) {
                container.setItem(slot, candidates.get(placed).copy());
                placed++;
            }
            slot++;
        }
        return placed;
    }
}
