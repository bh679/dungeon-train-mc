package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import games.brennan.dungeontrain.perks.effects.crafter.Reenchant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Re-enchant (Crafter): an item re-enchanted on a table reads "Re-enchanted 1 of 2", its stamp
 * against the viewer's {@code 1 + rank / 5}. Hidden for a viewer without the perk (or with perks off),
 * where "of 0" would mean nothing.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ReenchantTooltip {

    private ReenchantTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        int used = Reenchant.used(event.getItemStack());
        if (used <= 0) return;
        int allowed = CrafterPerks.reenchantsAllowed(CrafterRank.reenchant(player));
        if (allowed <= 0) return;
        event.getToolTip().add(Component.translatable("item.dungeontrain.reenchanted", used, allowed)
            .withStyle(ChatFormatting.DARK_PURPLE));
    }
}
