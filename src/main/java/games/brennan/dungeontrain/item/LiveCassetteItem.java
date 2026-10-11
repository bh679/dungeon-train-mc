package games.brennan.dungeontrain.item;

import net.mehvahdjukaar.vista.common.cassette.ITvCassette;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A cassette that is already tuned to the Live Feed. Its {@code vista:linked_feed} component is set
 * by the item's default components ({@code registry/ModItems}), so any Vista TV it goes into shows
 * whoever is broadcasting — no antenna, no linking step.
 */
public class LiveCassetteItem extends Item implements ITvCassette {

    public LiveCassetteItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public int getAnalogSignalStrengthInTv(ItemStack stack) {
        return 15;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.dungeontrain.live_cassette.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
