package games.brennan.dungeontrain.compat;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Removes {@link DisabledModContent} gear and ores from every generated loot table — chiefly
 * BetterNether's city/wither-tower chests and BetterEnd's End chests, which stock their own gear.
 * Wired to all tables by {@code data/neoforge/loot_modifiers/global_loot_modifiers.json}.
 */
public final class StripDisabledItemsLootModifier extends LootModifier {

    public static final MapCodec<StripDisabledItemsLootModifier> CODEC = RecordCodecBuilder.mapCodec(
        inst -> codecStart(inst).apply(inst, StripDisabledItemsLootModifier::new));

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, DungeonTrain.MOD_ID);

    public static final Supplier<MapCodec<StripDisabledItemsLootModifier>> SERIALIZER =
        SERIALIZERS.register("strip_disabled_mod_items", () -> CODEC);

    public StripDisabledItemsLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        ObjectArrayList<ItemStack> kept = new ObjectArrayList<>(generatedLoot.size());
        for (ItemStack stack : generatedLoot) {
            if (!DisabledModContent.isDisabledItem(stack)) {
                kept.add(stack);
            }
        }
        return kept;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
