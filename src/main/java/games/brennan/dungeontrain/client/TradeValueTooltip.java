package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.Optional;

/**
 * See Trade Values (Trader): hovering an item shows what it trades for (Trade Everything's value,
 * in emeralds and sixteenths), but only while that value is at most the rank's ceiling
 * ({@link #ceilingEmeralds}). The ceiling starts at 1 emerald and each rank adds
 * {@link #CEILING_STEP_GROWTH} more than the last (+1.8, +2.6, +3.4 ...): rank 10 sees up to
 * 46 emeralds, about five emerald blocks. Uncapped.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TradeValueTooltip {

    /** Extra emeralds each rank's step adds over the previous step. */
    public static final double CEILING_STEP_GROWTH = 0.8;
    private static final Boolean[] TE_LOADED = new Boolean[1];

    private TradeValueTooltip() {}

    /** Highest value (emeralds) visible at {@code rank}: {@code r + 0.4 r(r-1)}; 0 at rank 0. */
    public static double ceilingEmeralds(int rank) {
        int r = Math.max(0, rank);
        return r + CEILING_STEP_GROWTH * r * (r - 1) / 2.0;
    }

    /** Whether a value in sixteenths is visible at {@code rank}. */
    public static boolean visibleAt(int sixteenths, int rank) {
        return sixteenths > 0 && sixteenths <= ceilingEmeralds(rank) * 16.0;
    }

    /** "1 emerald", "2 emeralds 3/16" or "5/16 of an emerald" from a value in sixteenths. */
    public static String formatSixteenths(int sixteenths) {
        if (sixteenths <= 0) return "nothing";
        int whole = sixteenths / 16;
        int rest = sixteenths % 16;
        if (whole == 0) return rest + "/16 of an emerald";
        String em = whole == 1 ? "1 emerald" : whole + " emeralds";
        return rest == 0 ? em : em + " " + rest + "/16";
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        int rank = Perks.rank(player, TierOnePerks.SEE_TRADE_VALUES);
        if (rank <= 0) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        Optional<Integer> value = valueOf(stack);
        if (value.isEmpty() || !visibleAt(value.get(), rank)) return;
        event.getToolTip().add(Component.literal("Trades for " + formatSixteenths(value.get()))
            .withStyle(ChatFormatting.GREEN));
    }

    private static Optional<Integer> valueOf(ItemStack stack) {
        if (!teLoaded()) return Optional.empty();
        try {
            int v = games.brennan.tradeeverything.api.TradeEverythingApi.getValueSixteenths(stack);
            return v > 0 ? Optional.of(v) : Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static boolean teLoaded() {
        if (TE_LOADED[0] == null) TE_LOADED[0] = ModList.get().isLoaded("tradeeverything");
        return TE_LOADED[0];
    }
}
