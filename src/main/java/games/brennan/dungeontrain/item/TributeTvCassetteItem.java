package games.brennan.dungeontrain.item;

import net.mehvahdjukaar.vista.common.cassette.ITvCassette;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The cassette in a tribute room's TV. Tuned by its default {@code vista:linked_feed} component to
 * {@link games.brennan.dungeontrain.compat.vista.TributeTvBroadcast}, whose picture is the visitor's own
 * death screens; anybody else's TV plays snow on it. Shipped in the bundled home template and never
 * obtainable.
 */
public class TributeTvCassetteItem extends Item implements ITvCassette {

    public TributeTvCassetteItem(Properties properties) {
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
        tooltip.add(Component.translatable("item.dungeontrain.tribute_cassette.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
