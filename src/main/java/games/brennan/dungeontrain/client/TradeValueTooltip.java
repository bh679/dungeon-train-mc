package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * See Trade Values (Trader): hovering any item shows what it trades for (Trade Everything's
 * value for the stack, in emeralds and sixteenths). Rank 2 adds the nearest buyer: the closest
 * villager with a trade within {@link #BUYER_RANGE} blocks, with its profession and distance.
 * The client cannot see villagers' offers, so "best" is the nearest that trades at all.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TradeValueTooltip {

    /** Blocks around the player scanned for a buyer at rank 2. */
    public static final int BUYER_RANGE = 32;
    public static final int BUYER_RANK = 2;
    private static final Boolean[] TE_LOADED = new Boolean[1];

    private TradeValueTooltip() {}

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
        if (value.isEmpty()) return;
        List<Component> lines = event.getToolTip();
        lines.add(Component.literal("Trades for " + formatSixteenths(value.get()))
            .withStyle(ChatFormatting.GREEN));
        if (rank >= BUYER_RANK) {
            nearestBuyer(player).ifPresent(line -> lines.add(line.withStyle(ChatFormatting.DARK_GREEN)));
        }
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

    private static Optional<net.minecraft.network.chat.MutableComponent> nearestBuyer(LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(BUYER_RANGE);
        List<AbstractVillager> near = player.level().getEntitiesOfClass(AbstractVillager.class, box,
            v -> v.isAlive() && trades(v));
        return near.stream().min(Comparator.comparingDouble(player::distanceToSqr)).map(v -> {
            int dist = (int) Math.round(player.distanceTo(v));
            String who = v instanceof Villager vil
                ? vil.getVillagerData().getProfession().name().replace('_', ' ')
                : "wandering trader";
            return Component.literal("Nearest buyer: " + who + ", " + dist + " blocks away");
        });
    }

    private static boolean trades(AbstractVillager v) {
        if (v.isBaby()) return false;
        if (v instanceof Villager vil) {
            VillagerProfession p = vil.getVillagerData().getProfession();
            return p != VillagerProfession.NONE && p != VillagerProfession.NITWIT;
        }
        return true;
    }
}
