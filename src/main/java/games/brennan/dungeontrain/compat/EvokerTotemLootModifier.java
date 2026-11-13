package games.brennan.dungeontrain.compat;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.RandomTotemTable;
import games.brennan.dungeontrain.editor.VoidTotemGate;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * An evoker's totem is a Balanced Totem picked by the same weights as DT's {@code random_totem} loot
 * ({@link RandomTotemTable}), with no Void Totem before the train reaches the void ({@link VoidTotemGate}).
 *
 * <p>Balanced Totems swaps the evoker's vanilla totem for one of its own, uniformly. This runs after it
 * (DT loads after {@code balancedtotems}, so its {@code global_loot_modifiers.json} entry comes later) and
 * re-picks whatever totem is there, vanilla or Balanced. Scoped to {@code minecraft:entities/evoker} by
 * {@code data/dungeontrain/loot_modifiers/evoker_totem.json}.</p>
 */
public final class EvokerTotemLootModifier extends LootModifier {

    public static final MapCodec<EvokerTotemLootModifier> CODEC = RecordCodecBuilder.mapCodec(
        inst -> codecStart(inst).apply(inst, EvokerTotemLootModifier::new));

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, DungeonTrain.MOD_ID);

    public static final Supplier<MapCodec<EvokerTotemLootModifier>> SERIALIZER =
        SERIALIZERS.register("evoker_totem", () -> CODEC);

    public EvokerTotemLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        boolean voidReached = origin != null && VoidTotemGate.reached((int) Math.floor(origin.x));
        ObjectArrayList<ItemStack> out = new ObjectArrayList<>(generatedLoot.size());
        for (ItemStack stack : generatedLoot) {
            out.add(isTotem(stack) ? repick(stack, context.getRandom().nextLong(), voidReached) : stack);
        }
        return out;
    }

    private static boolean isTotem(ItemStack stack) {
        return stack.is(Items.TOTEM_OF_UNDYING)
            || RandomTotemTable.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** The weighted pick, or {@code stack} unchanged when no Balanced Totem is registered. */
    private static ItemStack repick(ItemStack stack, long seed, boolean voidReached) {
        return RandomTotemTable.pick(seed, BuiltInRegistries.ITEM::containsKey, voidReached)
            .map(BuiltInRegistries.ITEM::get)
            .map(item -> new ItemStack(item, stack.getCount()))
            .orElse(stack);
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
