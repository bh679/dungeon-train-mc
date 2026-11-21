package games.brennan.dungeontrain.perks.effects.crafter;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The ten enchantment groups Enchant Max and Enchant Bonus open one point at a time, in order:
 * protection, melee damage, Efficiency, Unbreaking, ranged, luck, movement, fire, knockback, then
 * Thorns and the trident's. Each is a tag, {@code dungeontrain:perk_group_<n>} (1 to 10), so a pack can
 * add its own enchantments to a group. Single-level enchantments (Silk Touch, Mending ...) are in none.
 */
public final class EnchantGroups {

    /** Lang key of a group's name, {@code <prefix><n>} for n = 1 to {@link CrafterPerks#GROUPS}. */
    public static final String NAME_KEY = "gui.dungeontrain.perks.enchant_group.";

    static final List<TagKey<Enchantment>> TAGS = IntStream.rangeClosed(1, CrafterPerks.GROUPS)
        .mapToObj(n -> TagKey.create(Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_group_" + n)))
        .toList();

    private EnchantGroups() {}

    /** The group (0 = first) the enchantment is in, or -1 when it is in none. */
    public static int indexOf(Holder<Enchantment> enchantment) {
        for (int i = 0; i < TAGS.size(); i++) {
            if (enchantment.is(TAGS.get(i))) return i;
        }
        return -1;
    }

    /** As {@link #indexOf(Holder)} for a bare enchantment, found through the registry; -1 when unregistered. */
    public static int indexOf(RegistryAccess access, Enchantment enchantment) {
        return access.registry(Registries.ENCHANTMENT)
            .map(r -> indexOf(r.wrapAsHolder(enchantment)))
            .orElse(-1);
    }

    /** As {@link #indexOf(Holder)} for an enchantment id; -1 when there is no such enchantment. */
    public static int indexOf(RegistryAccess access, ResourceLocation id) {
        return access.registry(Registries.ENCHANTMENT)
            .flatMap(r -> r.getHolder(id))
            .map(EnchantGroups::indexOf)
            .orElse(-1);
    }
}
