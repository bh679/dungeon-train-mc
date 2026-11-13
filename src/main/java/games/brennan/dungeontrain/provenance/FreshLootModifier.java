package games.brennan.dungeontrain.provenance;

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

import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Marks gear from every loot table as new ({@link ItemProvenance#markFresh}): structure chests,
 * fishing, mob drops, archaeology. Without it the first player to hold such an item would be told it
 * is older than the train remembers. Wired to all tables by
 * {@code data/neoforge/loot_modifiers/global_loot_modifiers.json}.
 */
public final class FreshLootModifier extends LootModifier {

    public static final MapCodec<FreshLootModifier> CODEC = RecordCodecBuilder.mapCodec(
        inst -> codecStart(inst).apply(inst, FreshLootModifier::new));

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, DungeonTrain.MOD_ID);

    public static final Supplier<MapCodec<FreshLootModifier>> SERIALIZER =
        SERIALIZERS.register("mark_fresh_gear", () -> CODEC);

    public FreshLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        for (ItemStack stack : generatedLoot) ItemProvenance.markFresh(stack, OptionalInt.empty());
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
