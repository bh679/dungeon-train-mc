package games.brennan.dungeontrain.mixin.deeperdarker;

import com.kyanite.deeperdarker.content.entities.SculkSnapper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Registry;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * A tamed sculk snapper near its owner now and then drops an enchanted book. Deeper Darker builds the
 * candidates by walking the whole enchantment registry in {@code tick}, skipping only the curses, and both
 * picks (the book's enchantment and the occasional extra one) come from that list. Mending is kept off it,
 * so a snapper is never a Mending source; every other enchantment, the drop rate and the book limit stay
 * the mod's own.
 *
 * <p>Wraps the {@code Registry.forEach} call rather than the synthetic {@code lambda$tick$0}, so a
 * renumbered lambda doesn't lose the hook. Guarded by {@code ThirdPartyMixinPlugin}
 * ({@code compat/mixinguard/ThirdPartyMixinTargets}).</p>
 */
@Mixin(SculkSnapper.class)
public abstract class SculkSnapperNoMendingMixin {

    @WrapOperation(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;forEach(Ljava/util/function/Consumer;)V"))
    private void dungeontrain$noMendingBooks(Registry<Enchantment> registry, Consumer<Enchantment> collect,
                                             Operation<Void> original) {
        Consumer<Enchantment> withoutMending = enchantment -> {
            if (!registry.wrapAsHolder(enchantment).is(Enchantments.MENDING)) collect.accept(enchantment);
        };
        original.call(registry, withoutMending);
    }
}
