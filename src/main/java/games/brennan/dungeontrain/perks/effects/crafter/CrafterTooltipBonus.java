package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * Enchant Bonus, the visible half: while a perk holder wears or holds an item, its tooltip shows each
 * enchantment one level higher, matching what the item does. Rewrites only the trailing
 * {@code enchantment.level.N} piece of each enchantment line; enchantments whose max is 1 show no
 * level in vanilla and are left alone.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CrafterTooltipBonus {

    private static final String LEVEL_KEY_PREFIX = "enchantment.level.";

    private CrafterTooltipBonus() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        Player player = event.getEntity();
        if (player == null) return;
        int bonus = CrafterPerks.bonusLevels(CrafterRank.enchantBonus(player));
        if (bonus <= 0 || !CrafterRank.isWornOrHeld(player, event.getItemStack())) return;
        List<Component> lines = event.getToolTip();
        for (int i = 0; i < lines.size(); i++) {
            Component raised = raiseLevel(lines.get(i), bonus);
            if (raised != null) lines.set(i, raised);
        }
    }

    /** A copy of {@code line} with its last {@code enchantment.level.N} sibling at N + bonus, or null. */
    static Component raiseLevel(Component line, int bonus) {
        List<Component> siblings = line.getSiblings();
        if (siblings.isEmpty()) return null;
        Component last = siblings.get(siblings.size() - 1);
        int level = levelOf(last);
        if (level <= 0) return null;
        MutableComponent copy = MutableComponent.create(line.getContents()).setStyle(line.getStyle());
        for (int i = 0; i < siblings.size() - 1; i++) copy.append(siblings.get(i));
        copy.append(Component.translatable(LEVEL_KEY_PREFIX + (level + bonus)).setStyle(last.getStyle()));
        return copy;
    }

    static int levelOf(Component piece) {
        if (!(piece.getContents() instanceof TranslatableContents t)) return 0;
        String key = t.getKey();
        if (!key.startsWith(LEVEL_KEY_PREFIX)) return 0;
        try {
            return Integer.parseInt(key.substring(LEVEL_KEY_PREFIX.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
