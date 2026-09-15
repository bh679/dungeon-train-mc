package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.item.VariantClipboardItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Effortless Building treat the {@link VariantClipboardItem} as something its shape modes
 * build with.
 *
 * <p>Effortless Building only arms a build sequence — the floor / wall / cube outline and the
 * second click — for items its {@code BuildPipeline.isBuildTriggerItem} accepts: block items, its
 * randomizer tool, filled buckets and digging tools. The clipboard is a plain {@code Item}, so
 * with it in hand the client never intercepts the click and a single cell is pasted as usual.
 * Answering {@code true} here puts the clipboard on the same client path a shovel takes
 * (outline-only preview, no item-usage count), and the server side of that path is
 * {@link games.brennan.dungeontrain.compat.EffortlessBuildingVariants}, which pastes every cell.</p>
 *
 * <p>{@code BuildPipeline} lives in the mod's common package, so this applies on both sides. Same
 * version-fragility note as {@link EffortlessBuildingPacketHandlerMixin}: the seam is
 * {@code effortlessbuilding-4.2+1.21.1}; {@code required: false} means a renamed method degrades
 * to "clipboard pastes one cell", never a crash. {@code remap = false}: another mod's class.</p>
 */
@Mixin(targets = "neoforge.nl.requios.effortlessbuilding.buildpipeline.BuildPipeline", remap = false)
public abstract class EffortlessBuildingTriggerItemMixin {

    @Inject(method = "isBuildTriggerItem", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$clipboardIsTriggerItem(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.getItem() instanceof VariantClipboardItem) cir.setReturnValue(true);
    }
}
