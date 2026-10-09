package games.brennan.dungeontrain.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;

import java.util.List;

/**
 * The broadcast camcorder: wear it on your head and everyone watching the Live Feed sees through
 * your eyes.
 *
 * <p>Head slot, no armour value. It stays on while you stream — vanilla's {@code CustomHeadLayer}
 * draws any non-armour head item with the model's {@code head} display, so the camcorder model is
 * visible to other players. The server-side {@code LiveFeedEvents} sees the equipment change and
 * tells this client to start streaming; taking it off stops the stream and keeps the item; being
 * cut off by another streamer burns it away. Found in chests ({@code ContainerContentsRoller}).</p>
 */
public class LiveHeadpieceItem extends Item implements Equipable {

    public LiveHeadpieceItem(Properties properties) {
        super(properties);
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    /** Right-click equips, the way armour does. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return this.swapWithEquipmentSlot(this, level, player, hand);
    }

    @Override
    public boolean canEquip(ItemStack stack, EquipmentSlot slot, LivingEntity entity) {
        return slot == EquipmentSlot.HEAD;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.dungeontrain.live_headpiece.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dungeontrain.live_headpiece.tooltip.warning").withStyle(ChatFormatting.DARK_GRAY));
    }
}
