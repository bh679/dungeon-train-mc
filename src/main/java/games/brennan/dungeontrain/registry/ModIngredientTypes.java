package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.DisposableCameraIngredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Mod-side custom recipe ingredient types. Mirrors {@link ModMobEffects}'s {@link DeferredRegister} pattern. */
public final class ModIngredientTypes {

    public static final DeferredRegister<IngredientType<?>> INGREDIENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPES, DungeonTrain.MOD_ID);

    /** An unshot disposable camera — the photo album recipe's camera slot. */
    public static final DeferredHolder<IngredientType<?>, IngredientType<DisposableCameraIngredient>> DISPOSABLE_CAMERA =
        INGREDIENT_TYPES.register("disposable_camera", () -> new IngredientType<>(DisposableCameraIngredient.CODEC));

    private ModIngredientTypes() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        INGREDIENT_TYPES.register(modBus);
    }
}
