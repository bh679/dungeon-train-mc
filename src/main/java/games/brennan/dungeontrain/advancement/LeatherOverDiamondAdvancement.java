package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;

/**
 * "Cover me in ...leather?" — granted when the player wears a full set of
 * leather armor whose combined armor points strictly exceed a full set of
 * vanilla diamond armor ({@value #DIAMOND_SET_ARMOR_TOTAL} total). Possible
 * because AIS (Adventure Item Stats) rerolls armor attribute modifiers — a
 * god-rolled leather set can legitimately out-stat diamond.
 *
 * <p>Each worn piece's effective armor is read from the
 * {@code minecraft:attribute_modifiers} data component (where AIS writes its
 * rolls), falling back to the item's vanilla default modifiers when the
 * component is absent — the same read used by
 * {@link games.brennan.dungeontrain.echo.EchoItemHighlights}. Only
 * {@code ADD_VALUE} entries count; multiply operations would skew a flat
 * point comparison.</p>
 *
 * <p>The advancement JSON
 * ({@code data/dungeontrain/advancement/dungeon_train/leather_over_diamond.json})
 * carries a single {@code minecraft:impossible} criterion, so it never fires on
 * its own — we award it directly here, exactly like
 * {@link NothingButBooksAdvancement}. "Summed rolled stats across four worn
 * slots beat a fixed threshold" isn't expressible as a single vanilla trigger,
 * so the direct-award pattern is the natural fit.</p>
 */
public final class LeatherOverDiamondAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stable id of the advancement. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/leather_over_diamond");

    /** Combined armor points of a full vanilla diamond set (3 + 8 + 6 + 3). */
    private static final double DIAMOND_SET_ARMOR_TOTAL = 20.0;

    /** The four worn armor slots, head to feet. */
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private LeatherOverDiamondAdvancement() {}

    /** True when {@code stack} is one of the four leather armor pieces. */
    private static boolean isLeatherArmor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.is(Items.LEATHER_HELMET)
            || stack.is(Items.LEATHER_CHESTPLATE)
            || stack.is(Items.LEATHER_LEGGINGS)
            || stack.is(Items.LEATHER_BOOTS);
    }

    /**
     * Effective armor points of {@code stack}: summed {@code ADD_VALUE}
     * {@link Attributes#ARMOR} modifiers from the item's attribute-modifiers
     * component (AIS rolls), or the vanilla defaults when the component is
     * absent.
     */
    @SuppressWarnings("deprecation") // Item.getDefaultAttributeModifiers — the vanilla baseline.
    private static double effectiveArmor(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (modifiers.modifiers().isEmpty()) {
            modifiers = stack.getItem().getDefaultAttributeModifiers();
        }
        return sumAddValue(modifiers, Attributes.ARMOR);
    }

    private static double sumAddValue(ItemAttributeModifiers modifiers, Holder<Attribute> attribute) {
        double total = 0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().equals(attribute)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                total += entry.modifier().amount();
            }
        }
        return total;
    }

    /**
     * True when every armor slot holds a leather piece and the set's combined
     * armor points strictly exceed {@value #DIAMOND_SET_ARMOR_TOTAL}.
     */
    private static boolean wearsLeatherBeatingDiamond(ServerPlayer player) {
        double total = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!isLeatherArmor(stack)) return false;
            total += effectiveArmor(stack);
        }
        return total > DIAMOND_SET_ARMOR_TOTAL;
    }

    /**
     * Evaluate the condition for {@code player} and award the advancement if
     * met.
     */
    public static void checkAndGrant(ServerPlayer player) {
        if (!wearsLeatherBeatingDiamond(player)) return;
        grant(player);
    }

    /**
     * Grant "Cover me in ...leather?" to {@code player}. Idempotent: returns
     * early when the advancement data isn't loaded or it's already earned, then
     * awards each criterion key (just the single {@code impossible} criterion).
     */
    public static void grant(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder self = mgr.get(ID);
        if (self == null) return; // advancement data not loaded (e.g. datapack stripped)
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return; // already earned

        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted 'Cover me in ...leather?' (full leather set beating diamond stats) to {}",
                player.getName().getString());
        }
    }
}
