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
 *
 * <p>Two of them exist: the first stream's, and the second stream's for the Two Streams reward
 * ({@code second}); they differ only in the feed they link and their tooltip.</p>
 */
public class LiveCassetteItem extends Item implements ITvCassette {

    private final boolean second;

    public LiveCassetteItem(Properties properties) {
        this(properties, false);
    }

    public LiveCassetteItem(Properties properties, boolean second) {
        super(properties);
        this.second = second;
    }

    /** Whether this cassette tunes a TV to the second stream. */
    public boolean isSecond() {
        return second;
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
        String key = second ? "item.dungeontrain.live_cassette_2.tooltip" : "item.dungeontrain.live_cassette.tooltip";
        tooltip.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
    }
}
