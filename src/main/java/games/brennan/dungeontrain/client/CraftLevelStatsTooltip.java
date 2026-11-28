package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import games.brennan.dungeontrain.perks.tier1.CraftLevelStats;
import games.brennan.dungeontrain.perks.tier1.CraftLevelStats.StatRange;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Craft Level Stats (Crafter): hovering gear in the crafting result slot, before taking it, turns
 * each stat line's number into the range the craft can roll: {@code 6 → 10.1 Attack Damage}.
 * The line keeps its own translation key and style, so every locale keeps its wording; only the
 * number argument changes. Anywhere else the tooltip is untouched.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CraftLevelStatsTooltip {

    /** Vanilla's and NeoForge's attribute line keys: argument 0 is the number, 1 the attribute name. */
    private static final Set<String> STAT_LINE_KEYS = Set.of(
        "attribute.modifier.equals.0", "attribute.modifier.plus.0", "neoforge.modifier.plus");
    private static final String ARROW = " → ";

    private CraftLevelStatsTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!CraftLevelStats.isGear(stack) || !isHoveredResult(stack)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        int rank = Perks.rank(player, TierOnePerks.CRAFT_LEVEL_STATS);
        Map<Holder<Attribute>, StatRange> ranges =
            CraftLevelStats.statRanges(stack, CraftLevelTierClient.levelTier(), rank);
        if (ranges.isEmpty()) return;
        Map<String, String> byName = new HashMap<>();
        ranges.forEach((attr, r) -> byName.put(attr.value().getDescriptionId(), rangeText(r)));
        List<Component> lines = event.getToolTip();
        for (int i = 0; i < lines.size(); i++) {
            Component rewritten = rewrite(lines.get(i), byName);
            if (rewritten != null) lines.set(i, rewritten);
        }
    }

    /** {@code "6 → 10.1"} in vanilla's own number format; a single number when the range is flat. */
    static String rangeText(StatRange r) {
        String min = ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(r.min());
        String max = ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(r.max());
        return min.equals(max) ? min : min + ARROW + max;
    }

    private static boolean isHoveredResult(ItemStack stack) {
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot slot = screen.getSlotUnderMouse();
        return slot instanceof ResultSlot && slot.getItem() == stack;
    }

    /** A copy of {@code line} with its stat number replaced, or null when it isn't one of the stats. */
    private static Component rewrite(Component line, Map<String, String> byName) {
        boolean changed = false;
        MutableComponent self = null;
        if (line.getContents() instanceof TranslatableContents tc && STAT_LINE_KEYS.contains(tc.getKey())) {
            Object[] args = tc.getArgs();
            String range = args.length == 2 ? byName.get(attributeKey(args[1])) : null;
            if (range != null) {
                Object[] newArgs = {range, args[1]};
                self = MutableComponent.create(new TranslatableContents(tc.getKey(), tc.getFallback(), newArgs));
                changed = true;
            }
        }
        if (self == null) self = MutableComponent.create(line.getContents());
        self.setStyle(line.getStyle());
        for (Component sibling : line.getSiblings()) {
            Component r = rewrite(sibling, byName);
            if (r != null) changed = true;
            self.append(r != null ? r : sibling);
        }
        return changed ? self : null;
    }

    private static String attributeKey(Object arg) {
        return arg instanceof Component c && c.getContents() instanceof TranslatableContents tc ? tc.getKey() : null;
    }
}
