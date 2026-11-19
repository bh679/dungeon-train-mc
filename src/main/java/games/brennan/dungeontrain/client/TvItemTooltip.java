package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * Labels a Vista TV's hover text with its state, just under the name: "On" or "Off", then the stream it
 * remembers ("Off · Stream 2"). Each combination is its own stack ({@code tv_on}, {@code tv_stream}), and
 * without the line two TV stacks that won't merge look identical. A TV with no remembered stream gets
 * no channel: it follows the "TV on your head shows" option.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TvItemTooltip {

    static final String ON_KEY = "item.dungeontrain.tv.on";
    static final String OFF_KEY = "item.dungeontrain.tv.off";
    static final String STREAM_1_KEY = "gui.dungeontrain.tv.channel.main";
    static final String STREAM_2_KEY = "gui.dungeontrain.tv.channel.second";

    private static final String SEPARATOR = " · ";

    private TvItemTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!TvItems.isTv(stack)) return;
        List<Component> lines = event.getToolTip();
        lines.add(Math.min(1, lines.size()), label(TvItems.isOn(stack), TvItems.stream(stack)));
    }

    private static Component label(boolean on, int stream) {
        MutableComponent line = Component.empty();
        List<String> keys = labelKeys(on, stream);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) line.append(SEPARATOR);
            line.append(Component.translatable(keys.get(i)));
        }
        return line.withStyle(ChatFormatting.GRAY);
    }

    /** The label's translation keys, in order: on/off, then the stream (1 or 2) when one is remembered. Pure. */
    static List<String> labelKeys(boolean on, int stream) {
        String power = on ? ON_KEY : OFF_KEY;
        return switch (stream) {
            case 1 -> List.of(power, STREAM_1_KEY);
            case 2 -> List.of(power, STREAM_2_KEY);
            default -> List.of(power);
        };
    }
}
