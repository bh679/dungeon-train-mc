package games.brennan.dungeontrain.perks.effects.lover;

import games.brennan.dungeontrain.perks.effects.lover.GoodGifterMath.Offering;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sorts what a player holds out to an animal into a {@link Offering}, from item tags.
 *
 * <p>An animal's diet (meat eater, plant eater or both) is found once per entity type by asking
 * the animal whether it eats anything in each diet's tags, then cached. "Similar food" is any item
 * in one of the animal's diets; "other animal food" is anything in a vanilla {@code *_food} tag.</p>
 */
final class GoodGifterOfferings {

    private enum Diet {
        MEAT(List.of(ItemTags.MEAT, ItemTags.FISHES, ItemTags.WOLF_FOOD, ItemTags.CAT_FOOD,
            ItemTags.OCELOT_FOOD, ItemTags.AXOLOTL_FOOD)),
        PLANTS(List.of(Tags.Items.CROPS, Tags.Items.SEEDS, ItemTags.FLOWERS, ItemTags.COW_FOOD,
            ItemTags.SHEEP_FOOD, ItemTags.PIG_FOOD, ItemTags.CHICKEN_FOOD, ItemTags.HORSE_FOOD,
            ItemTags.LLAMA_FOOD, ItemTags.GOAT_FOOD, ItemTags.RABBIT_FOOD, ItemTags.CAMEL_FOOD,
            ItemTags.PANDA_FOOD, ItemTags.PARROT_FOOD, ItemTags.TURTLE_FOOD, ItemTags.SNIFFER_FOOD,
            ItemTags.ARMADILLO_FOOD, ItemTags.BEE_FOOD, ItemTags.FOX_FOOD));

        final List<TagKey<Item>> tags;

        Diet(List<TagKey<Item>> tags) {
            this.tags = tags;
        }

        boolean contains(ItemStack stack) {
            for (TagKey<Item> tag : tags) {
                if (stack.is(tag)) return true;
            }
            return false;
        }
    }

    /** Every vanilla animal food tag; an item in any of them is "other animal food". */
    private static final List<TagKey<Item>> ANIMAL_FOOD_TAGS = List.of(
        ItemTags.WOLF_FOOD, ItemTags.CAT_FOOD, ItemTags.OCELOT_FOOD, ItemTags.AXOLOTL_FOOD,
        ItemTags.COW_FOOD, ItemTags.SHEEP_FOOD, ItemTags.PIG_FOOD, ItemTags.CHICKEN_FOOD,
        ItemTags.HORSE_FOOD, ItemTags.LLAMA_FOOD, ItemTags.GOAT_FOOD, ItemTags.RABBIT_FOOD,
        ItemTags.CAMEL_FOOD, ItemTags.PANDA_FOOD, ItemTags.PARROT_FOOD, ItemTags.TURTLE_FOOD,
        ItemTags.SNIFFER_FOOD, ItemTags.ARMADILLO_FOOD, ItemTags.BEE_FOOD, ItemTags.FOX_FOOD,
        ItemTags.FROG_FOOD, ItemTags.HOGLIN_FOOD, ItemTags.STRIDER_FOOD);

    private static final Map<EntityType<?>, Set<Diet>> DIETS = new ConcurrentHashMap<>();

    private GoodGifterOfferings() {}

    static Offering classify(Animal animal, ItemStack stack) {
        if (stack.isEmpty()) return Offering.NOTHING;
        if (animal.isFood(stack)) return Offering.RIGHT_FOOD;
        for (Diet diet : dietOf(animal)) {
            if (diet.contains(stack)) return Offering.SIMILAR_FOOD;
        }
        for (TagKey<Item> tag : ANIMAL_FOOD_TAGS) {
            if (stack.is(tag)) return Offering.OTHER_ANIMAL_FOOD;
        }
        if (stack.has(DataComponents.FOOD)) return Offering.PLAYER_FOOD;
        return Offering.ANY_ITEM;
    }

    /** Tags change on datapack reload; drop the cache so diets are worked out again. */
    static void clearCache() {
        DIETS.clear();
    }

    private static Set<Diet> dietOf(Animal animal) {
        return DIETS.computeIfAbsent(animal.getType(), type -> {
            Set<Diet> diets = EnumSet.noneOf(Diet.class);
            for (Diet diet : Diet.values()) {
                if (eatsAnyOf(animal, diet)) diets.add(diet);
            }
            return diets;
        });
    }

    private static boolean eatsAnyOf(Animal animal, Diet diet) {
        for (TagKey<Item> tag : diet.tags) {
            for (Holder<Item> item : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                if (animal.isFood(new ItemStack(item))) return true;
            }
        }
        return false;
    }
}
